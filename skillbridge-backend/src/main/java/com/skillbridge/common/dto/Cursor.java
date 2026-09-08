package com.skillbridge.common.dto;

import com.skillbridge.common.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * A composite keyset cursor: {@code (timestamp, id)}, base64url-encoded.
 *
 * <p>The id is not decoration. A cursor on the timestamp alone breaks whenever
 * two rows share it — and in an audit log they do, constantly: a single request
 * that writes several entries stamps them within the same
 * {@code LocalDateTime}. Seeking with {@code occurred_at < :cursor} then skips
 * every tied row that did not fit on the previous page, and seeking with
 * {@code <=} repeats them. Neither is acceptable in an audit trail, and both
 * fail silently. The tie-break makes the ordering total, so the seek is exact.
 *
 * <p>Encoded rather than exposed as two query parameters so it stays opaque:
 * a client that cannot read the cursor cannot depend on its shape, and the sort
 * key can change later without breaking anyone mid-scroll.
 */
public record Cursor(LocalDateTime timestamp, Long id) {

    private static final String SEPARATOR = "|";

    /**
     * The cursor for the first page: strictly after every real row.
     *
     * <p>This exists so the first page and the tenth are the <em>same</em>
     * query. The obvious alternative, {@code (:cursorTime IS NULL OR ...)},
     * looks fine and fails at runtime: a null bound through JDBC carries no
     * type, Postgres infers {@code bytea}, and the row comparison dies with
     * "operator does not exist: timestamp without time zone < bytea". The
     * alternatives are casting inside the query or a second finder per scope;
     * a sentinel is cheaper than either and leaves one code path to test.
     *
     * <p>Year 9999 rather than {@link LocalDateTime#MAX}, whose year is far
     * outside what a Postgres {@code timestamp} can represent.
     */
    public static Cursor start() {
        return new Cursor(LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_999), Long.MAX_VALUE);
    }

    public String encode() {
        String raw = timestamp.toString() + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a cursor, or {@link #start()} for a blank one — the first page.
     *
     * <p>A malformed cursor is a 400 rather than a silent fall back to the
     * first page: silently restarting the feed looks like duplicated rows to
     * anyone scrolling, which in an audit view reads as tampering.
     */
    public static Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return start();
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Malformed cursor.");
        }

        int split = raw.lastIndexOf(SEPARATOR);
        if (split < 0) {
            throw new BadRequestException("Malformed cursor.");
        }
        try {
            return new Cursor(
                    LocalDateTime.parse(raw.substring(0, split)),
                    Long.parseLong(raw.substring(split + 1)));
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new BadRequestException("Malformed cursor.");
        }
    }
}
