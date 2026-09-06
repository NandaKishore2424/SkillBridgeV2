package com.skillbridge.common.dto;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns {@code [ownerId, relatedId]} rows from a grouped query into a lookup.
 *
 * <p>Three list endpoints needed the identical fold — students to their
 * enrolled batches, trainers to their assigned batches, companies to their
 * linked batches — and each of them is one query per page rather than one per
 * row. Written once so the next one does not reinvent it, and so the
 * "one query, then group in memory" shape stays visible as the intended
 * pattern.
 */
public final class IdGrouping {

    private IdGrouping() {
    }

    public static Map<Long, List<Long>> byOwner(List<Object[]> rows) {
        return rows.stream().collect(Collectors.groupingBy(
                row -> (Long) row[0],
                Collectors.mapping(row -> (Long) row[1], Collectors.toList())));
    }

    /** Empty list rather than null, so callers need no null check. */
    public static List<Long> forOwner(Map<Long, List<Long>> grouped, Long ownerId) {
        return grouped.getOrDefault(ownerId, Collections.emptyList());
    }
}
