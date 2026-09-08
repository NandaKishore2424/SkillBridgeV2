package com.skillbridge.common.dto;

import com.skillbridge.common.exception.BadRequestException;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@code ?sort=field,dir} query parameter into a {@link Sort}, against
 * an allowlist.
 *
 * <p>The allowlist is the point. Spring will happily bind {@code ?sort=} to any
 * property name and then fail deep inside the query with a
 * {@code PropertyReferenceException} — a 500 that any client can trigger by
 * typing. Worse, an open sort lets a caller order by a column that is neither
 * indexed nor meant to be visible: sorting a list of people by a field you
 * cannot read still tells you its ordering, which is a slow but real
 * information leak.
 *
 * <p>So each endpoint declares the fields it supports, mapped from the name the
 * API exposes to the entity path underneath. That indirection matters twice:
 * it keeps the wire vocabulary stable when a field moves in the schema, and it
 * means the set of sortable columns is a deliberate list somebody chose rather
 * than whatever the entity happens to have.
 *
 * <p>An unknown field is a 400 naming the ones that work, not a silent fallback
 * to the default. Silently ignoring the sort a caller asked for produces a
 * screen that looks sorted and is not.
 */
public final class SortParameter {

    private SortParameter() {
    }

    /**
     * Builds an allowlist. Keys are the names clients send, values the entity
     * property paths.
     *
     * <p>Ordered, so the error message lists them the way the caller would
     * expect to read them rather than in hash order.
     */
    public static Map<String, String> allow(String... apiNameThenPath) {
        if (apiNameThenPath.length % 2 != 0) {
            throw new IllegalArgumentException("expected name/path pairs");
        }
        Map<String, String> allowed = new LinkedHashMap<>();
        for (int i = 0; i < apiNameThenPath.length; i += 2) {
            allowed.put(apiNameThenPath[i], apiNameThenPath[i + 1]);
        }
        return allowed;
    }

    /**
     * Parses {@code field} or {@code field,dir}.
     *
     * @param raw       the raw parameter; blank or null yields {@code fallback}
     * @param allowed   API field name to entity property path
     * @param fallback  the ordering to use when none was asked for
     * @throws BadRequestException if the field is not on the allowlist, or the
     *                             direction is neither {@code asc} nor {@code desc}
     */
    public static Sort parse(String raw, Map<String, String> allowed, Sort fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        String[] parts = raw.split(",", 2);
        String field = parts[0].trim();
        String path = allowed.get(field);
        if (path == null) {
            throw new BadRequestException(
                    "Cannot sort by '%s'. Sortable fields: %s"
                            .formatted(field, String.join(", ", allowed.keySet())));
        }

        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2 && !parts[1].isBlank()) {
            String requested = parts[1].trim().toLowerCase(Locale.ROOT);
            direction = switch (requested) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default -> throw new BadRequestException(
                        "Sort direction must be 'asc' or 'desc', not '%s'".formatted(requested));
            };
        }

        return Sort.by(direction, path);
    }
}
