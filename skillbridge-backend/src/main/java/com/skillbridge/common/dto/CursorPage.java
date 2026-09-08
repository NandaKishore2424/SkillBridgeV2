package com.skillbridge.common.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

/**
 * One page of a keyset-paginated feed.
 *
 * <p>Deliberately not {@link PagedResponse}. That envelope carries
 * {@code totalElements} and {@code totalPages}, and producing either means a
 * {@code COUNT(*)} over the whole filtered set on every request — which is the
 * cost keyset pagination exists to avoid. Offering the fields and filling them
 * with zeroes would be worse than omitting them.
 *
 * <p>What you give up with them is random access: there is no "jump to page
 * 500". That is the right trade for an append-only feed read newest-first and
 * scrolled, and the wrong one for a numbered pager, which is why only the audit
 * log uses this.
 */
@Data
@Builder
public class CursorPage<T> {

    private List<T> items;

    /**
     * Opaque token for the next request, or null at the end of the feed.
     *
     * <p>Opaque on purpose. It encodes the sort key of the last row returned,
     * and keeping clients from parsing it means the ordering can change later
     * without breaking anyone holding a cursor.
     */
    private String nextCursor;

    /**
     * Whether another page exists.
     *
     * <p>Determined by asking for one row more than requested and discarding
     * it, so it costs nothing beyond the row itself. {@code nextCursor != null}
     * says the same thing; both are here because a client rendering a "load
     * more" button wants the boolean and a client fetching wants the token.
     */
    private boolean hasMore;

    /** Rows actually returned, which is at most the requested size. */
    private int size;

    public static <E, T> CursorPage<T> of(List<E> rows, int requestedSize,
                                           Function<E, T> mapper,
                                           Function<E, String> cursorOf) {
        boolean hasMore = rows.size() > requestedSize;
        List<E> page = hasMore ? rows.subList(0, requestedSize) : rows;

        return CursorPage.<T>builder()
                .items(page.stream().map(mapper).toList())
                .hasMore(hasMore)
                // The cursor is the last row of THIS page, not the extra row:
                // the next request seeks strictly past it. Taking it from the
                // discarded row would skip that row entirely.
                .nextCursor(hasMore && !page.isEmpty() ? cursorOf.apply(page.get(page.size() - 1)) : null)
                .size(page.size())
                .build();
    }
}
