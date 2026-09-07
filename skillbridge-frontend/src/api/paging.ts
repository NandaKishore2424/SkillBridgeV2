/**
 * The wire shape of a paginated list response.
 *
 * Mirrors `com.skillbridge.common.dto.PagedResponse`. The collection is
 * `items`, not `content` — the backend deliberately diverged from Spring's own
 * name because every screen here already read `items`.
 *
 * `first`, `last` and `sort` are sent by the backend and typed here so a pager
 * never has to derive them: `page === totalPages - 1` is wrong on an empty
 * result, where `totalPages` is 0.
 */
export interface PagedResponse<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
  sort: string
}

/** Page request parameters, as every list endpoint accepts them. */
export interface PageParams {
  page?: number
  size?: number
}

/**
 * The items of a page, for a caller that genuinely wants the whole list.
 *
 * A screen that has not been given a pager yet still needs an array. Going
 * through this rather than reaching for `.items` inline keeps those call sites
 * greppable — they are the ones that silently stop at the first 20 rows, and
 * each one is a pager still to be built.
 */
export function itemsOf<T>(page: PagedResponse<T>): T[] {
  return page.items
}
