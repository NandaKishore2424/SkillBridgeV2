package com.skillbridge.bulkupload.importer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvImportFileTest {

    private static final String HEADER = "Full Name,Email,Roll Number,Degree,Branch,Year\n";

    private static CsvImportFile parse(String text) {
        return CsvImportFile.parse(text.getBytes(StandardCharsets.UTF_8), ImportKind.STUDENT, 5);
    }

    private static List<ImportRow> rows(CsvImportFile file) {
        List<ImportRow> rows = new ArrayList<>();
        file.forEachRow(rows::add);
        return rows;
    }

    @Test
    @DisplayName("rows are numbered as a spreadsheet shows them, blank lines skipped but counted")
    void rowNumbers() {
        CsvImportFile file = parse(HEADER + "A,a@x.edu,R1,,,\n\nB,b@x.edu,R2,,,\n");

        assertThat(file.rowCount()).isEqualTo(2);
        assertThat(rows(file)).extracting(ImportRow::rowNumber).containsExactly(2, 4);
        assertThat(rows(file).get(1).values()).containsEntry("Full Name", "B").containsEntry("Roll Number", "R2");
    }

    @Test
    @DisplayName("headers match whatever the case and spacing; optional columns may be left out")
    void headersAreForgiving() {
        CsvImportFile file = parse("  full   name ,EMAIL,roll number\nA,a@x.edu,R1\n");

        assertThat(rows(file).get(0).values()).containsOnlyKeys("Full Name", "Email", "Roll Number");
    }

    @Test
    @DisplayName("a misspelt column is refused, naming it, rather than silently dropping its data")
    void unknownColumn() {
        assertThatThrownBy(() -> parse("Full Name,Email,Roll Number,Branh\nA,a@x.edu,R1,CSE\n"))
                .isInstanceOf(InvalidImportFileException.class)
                .hasMessageContaining("Unknown column(s): Branh");
    }

    @Test
    @DisplayName("a missing required column is refused")
    void missingColumn() {
        assertThatThrownBy(() -> parse("Full Name,Email\nA,a@x.edu\n"))
                .isInstanceOf(InvalidImportFileException.class)
                .hasMessageContaining("Missing required column(s): Roll Number");
    }

    @Test
    @DisplayName("a repeated column is refused")
    void duplicateColumn() {
        assertThatThrownBy(() -> parse("Full Name,Email,Email,Roll Number\nA,a,b,R\n"))
                .isInstanceOf(InvalidImportFileException.class)
                .hasMessageContaining("appears twice");
    }

    @Test
    @DisplayName("bytes that are not UTF-8 are refused instead of turning into question marks")
    void notUtf8() {
        byte[] latin1 = (HEADER + "Zoë,z@x.edu,R1,,,\n").getBytes(StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> CsvImportFile.parse(latin1, ImportKind.STUDENT, 5))
                .isInstanceOf(InvalidImportFileException.class)
                .hasMessageContaining("not UTF-8");
    }

    @Test
    @DisplayName("Excel's byte-order mark does not become part of the first header")
    void byteOrderMark() {
        CsvImportFile file = parse("﻿" + HEADER + "A,a@x.edu,R1,,,\n");

        assertThat(rows(file).get(0).values()).containsEntry("Full Name", "A");
    }

    @Test
    @DisplayName("more rows than the limit is refused before anything is imported")
    void tooManyRows() {
        assertThatThrownBy(() -> parse(HEADER + "A,a@x.edu,R1,,,\n".repeat(6)))
                .isInstanceOf(InvalidImportFileException.class)
                .hasMessageContaining("more than 5 rows");
    }

    @Test
    @DisplayName("a header with no rows, or nothing at all, is refused")
    void empty() {
        assertThatThrownBy(() -> parse(HEADER)).hasMessageContaining("no rows");
        assertThatThrownBy(() -> parse("")).hasMessageContaining("empty");
    }

    @Test
    @DisplayName("a row with the wrong number of values fails alone; the file is still importable")
    void raggedRowIsARowProblem() {
        CsvImportFile file = parse(HEADER + "A,a@x.edu,R1\nB,b@x.edu,R2,,,\n");

        List<ImportRow> rows = rows(file);
        assertThat(rows.get(0).problem()).contains("Expected 6 values, found 3");
        assertThat(rows.get(1).problem()).isNull();
    }

    @Test
    @DisplayName("an unclosed quote is a file error with a line number")
    void brokenQuoting() {
        assertThatThrownBy(() -> parse(HEADER + "\"A,a@x.edu,R1,,,\nB,b@x.edu,R2,,,\n"))
                .isInstanceOf(InvalidImportFileException.class)
                .hasMessageContaining("not valid CSV");
    }

    @Test
    @DisplayName("the downloadable template is itself a valid file")
    void templatesParse() {
        for (ImportKind kind : ImportKind.values()) {
            CsvImportFile file = CsvImportFile.parse(kind.template().getBytes(StandardCharsets.UTF_8), kind, 5);
            assertThat(rows(file)).singleElement().satisfies(r -> assertThat(r.problem()).isNull());
        }
    }
}
