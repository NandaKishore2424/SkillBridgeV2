package com.skillbridge.common.dto;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Builds page requests with a hard ceiling on size.
 *
 * <p>Every list endpoint read {@code page} and {@code size} straight from the
 * query string and passed them to {@code PageRequest.of} unchecked, so
 * {@code ?size=1000000} was honoured: one HTTP request made Postgres read and
 * materialise the whole table, and Jackson then serialise it. Verified against
 * the live service before this class was written — {@code /admin/batches?size=1000000}
 * came back reporting a page size of 1000000.
 *
 * <p>The audit log had its own clamp because that table grows fastest and the
 * risk was noticed there first. This generalises it rather than leaving five
 * other endpoints to be found one at a time.
 *
 * <p>The cap is silent rather than a 400. A client asking for too much wants as
 * much as it can have, and the response reports the size it actually got, so an
 * honest client can see what happened. A hostile one is capped either way.
 */
public final class Pagination {

    /** Enough for any real screen; small enough that no single request hurts. */
    public static final int MAX_PAGE_SIZE = 100;

    public static final int DEFAULT_PAGE_SIZE = 20;

    private Pagination() {
    }

    public static PageRequest of(int page, int size) {
        return PageRequest.of(clampPage(page), clampSize(size));
    }

    public static PageRequest of(int page, int size, Sort sort) {
        return PageRequest.of(clampPage(page), clampSize(size), sort);
    }

    /** Negative pages are a client bug, not an attack; treat them as the first page. */
    public static int clampPage(int page) {
        return Math.max(page, 0);
    }

    public static int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
