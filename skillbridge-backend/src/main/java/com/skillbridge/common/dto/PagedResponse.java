package com.skillbridge.common.dto;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * One page of results.
 *
 * <p>The collection is called {@code items}, not {@code content}. Spring's own
 * {@code Page} uses {@code content} and it is tempting to match it, but every
 * screen in the React app already reads {@code items} — renaming it would be a
 * silent breaking change for a cosmetic gain.
 *
 * <p>{@code first}, {@code last} and {@code sort} exist so a client can render a
 * pager without arithmetic. Deriving "am I on the last page" from
 * {@code page == totalPages - 1} is easy to get wrong on an empty result set,
 * where {@code totalPages} is 0.
 */
@Data
@Builder
public class PagedResponse<T> {

    private List<T> items;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;

    /** Sort as applied, e.g. {@code "startDate: DESC"}; {@code "UNSORTED"} if none. */
    private String sort;

    /** Wraps a page whose elements are already the wire type. */
    public static <T> PagedResponse<T> from(Page<T> page) {
        return from(page, Function.identity());
    }

    /**
     * Wraps a page of entities, mapping each to its wire type.
     *
     * <p>Mapping here rather than at the call site keeps the paging metadata and
     * the content in step: it is otherwise easy to map the content and then
     * build the metadata from a different page object.
     */
    public static <E, T> PagedResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return PagedResponse.<T>builder()
                .items(page.getContent().stream().map(mapper).toList())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .sort(page.getSort().isSorted() ? page.getSort().toString() : "UNSORTED")
                .build();
    }
}
