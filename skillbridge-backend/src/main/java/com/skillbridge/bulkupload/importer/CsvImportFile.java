package com.skillbridge.bulkupload.importer;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import com.opencsv.exceptions.CsvMalformedLineException;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A CSV upload, checked as a whole before any row is imported.
 *
 * <p>{@link #parse} reads the entire file once, in the admin's request, and
 * throws {@link InvalidImportFileException} for anything that makes the file
 * unusable: bytes that are not UTF-8, a missing or unknown column, broken
 * quoting, no rows, too many rows. The old importer found these on its
 * background thread, where the only trace was a FAILED upload with a parser
 * message; one malformed row failed the whole file.
 *
 * <p>{@link #forEachRow} then reads the rows again, one at a time, for the
 * importer. A row with the wrong number of values is handed over with a
 * {@code problem} and becomes a failed row, not a failed file.
 *
 * <p>The text is held in memory. That is bounded by the multipart limit
 * ({@code spring.servlet.multipart.max-file-size}), and it has to be: the
 * servlet container deletes its copy of the upload when the request ends, and
 * the import outlives the request.
 */
public final class CsvImportFile {

    private static final char BOM = '﻿';

    private final String text;
    private final ImportKind kind;
    private final List<String> columns;
    private final int rowCount;

    private CsvImportFile(String text, ImportKind kind, List<String> columns, int rowCount) {
        this.text = text;
        this.kind = kind;
        this.columns = columns;
        this.rowCount = rowCount;
    }

    public static CsvImportFile parse(byte[] data, ImportKind kind, int maxRows) {
        String text = strictUtf8(data);
        try (CSVReader reader = reader(text)) {
            List<String> columns = header(reader.readNext(), kind);
            int rows = 0;
            String[] row;
            while ((row = reader.readNext()) != null) {
                if (!isBlank(row) && ++rows > maxRows) {
                    throw new InvalidImportFileException("The file has more than " + maxRows
                            + " rows. Split it and upload each part.");
                }
            }
            if (rows == 0) {
                throw new InvalidImportFileException("The file has a header but no rows to import.");
            }
            return new CsvImportFile(text, kind, columns, rows);
        } catch (CsvMalformedLineException e) {
            // Its own message quotes the rest of the file; the line is what helps.
            throw new InvalidImportFileException("The file is not valid CSV: a quote opened on or before line "
                    + e.getLineNumber() + " is never closed.");
        } catch (CsvException e) {
            throw new InvalidImportFileException("The file is not valid CSV near line " + e.getLineNumber()
                    + ": " + e.getMessage());
        } catch (IOException e) {
            throw new InvalidImportFileException("The file could not be read as CSV: " + e.getMessage());
        }
    }

    public ImportKind kind() {
        return kind;
    }

    /** Non-blank data rows. */
    public int rowCount() {
        return rowCount;
    }

    /** Streams the data rows in file order, skipping blank ones. */
    public void forEachRow(Consumer<ImportRow> consumer) {
        try (CSVReader reader = reader(text)) {
            reader.readNext(); // header, already checked
            String[] row;
            while ((row = reader.readNext()) != null) {
                if (isBlank(row)) {
                    continue;
                }
                // Lines read so far: the spreadsheet row number of this record
                // (the header is row 1). For a quoted value spanning lines, the
                // last of them.
                int rowNumber = (int) reader.getLinesRead();
                consumer.accept(toRow(rowNumber, row));
            }
        } catch (CsvException e) {
            throw new IllegalStateException("A file that parsed once failed to parse again", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ImportRow toRow(int rowNumber, String[] row) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(row.length, columns.size()); i++) {
            values.put(columns.get(i), row[i].trim());
        }
        String problem = row.length == columns.size() ? null
                : "Expected " + columns.size() + " values, found " + row.length + ". Check for a stray comma.";
        return new ImportRow(rowNumber, values, problem);
    }

    private static List<String> header(String[] header, ImportKind kind) {
        if (header == null || isBlank(header)) {
            throw new InvalidImportFileException("The file is empty.");
        }
        Map<String, String> canonical = new LinkedHashMap<>();
        kind.columns().forEach(c -> canonical.put(normalise(c), c));

        List<String> columns = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String name : header) {
            String column = canonical.get(normalise(name));
            if (column == null) {
                unknown.add(name.trim());
            } else if (columns.contains(column)) {
                throw new InvalidImportFileException("The column \"" + column + "\" appears twice.");
            } else {
                columns.add(column);
            }
        }
        // Unknown is an error, not something to ignore: a misspelt optional column
        // ("Branh") would otherwise drop that data from every row without a word.
        if (!unknown.isEmpty()) {
            throw new InvalidImportFileException("Unknown column(s): " + String.join(", ", unknown)
                    + ". The columns are: " + String.join(", ", kind.columns()) + ".");
        }
        List<String> missing = kind.required().stream().filter(c -> !columns.contains(c)).toList();
        if (!missing.isEmpty()) {
            throw new InvalidImportFileException("Missing required column(s): " + String.join(", ", missing) + ".");
        }
        return columns;
    }

    /** Case, surrounding space and repeated inner spaces do not matter in a header. */
    private static String normalise(String header) {
        return header.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * Refuses bytes that are not UTF-8 instead of substituting U+FFFD, which
     * would store names with question marks in them. Excel's "CSV UTF-8" adds a
     * byte-order mark; it is dropped.
     */
    private static String strictUtf8(byte[] data) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
            return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw new InvalidImportFileException(
                    "The file is not UTF-8. In Excel, save it as \"CSV UTF-8 (Comma delimited)\".");
        }
    }

    private static CSVReader reader(String text) {
        return new CSVReaderBuilder(new StringReader(text)).build();
    }

    private static boolean isBlank(String[] row) {
        for (String value : row) {
            if (!value.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
