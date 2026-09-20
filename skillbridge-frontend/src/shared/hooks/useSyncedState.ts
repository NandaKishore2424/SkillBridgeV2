import { useState, type Dispatch, type SetStateAction } from 'react'

/**
 * Editable state seeded from something that can change underneath it.
 *
 * Seven places did this with an effect:
 *
 * ```tsx
 * const [selected, setSelected] = useState([])
 * useEffect(() => { if (data) setSelected(data.map(d => d.id)) }, [data])
 * ```
 *
 * That renders once with the old value, commits, then sets state and renders
 * again -- a visible flash of the previous selection on every refetch, and the
 * cascading render React now warns about. Adjusting state *during* the render
 * that noticed the change is React's own answer (react.dev, "You might not need
 * an effect"): the stale render is thrown away before the browser sees it.
 *
 * @param source what the state should become when `key` changes
 * @param key    what counts as "changed", compared with `Object.is`. Defaults
 *               to `source`, which is right for a primitive and wrong for an
 *               object or array literal -- those get a new identity every
 *               render, so the state would reset on every keystroke. Pass an id
 *               or a primitive in that case.
 */
export function useSyncedState<T>(
  source: T,
  key: unknown = source,
): [T, Dispatch<SetStateAction<T>>] {
  const [value, setValue] = useState<T>(source)
  const [lastKey, setLastKey] = useState<unknown>(key)

  if (!Object.is(lastKey, key)) {
    // Setting state during render of this same component is allowed, and is
    // what makes this cheaper than an effect rather than more expensive: React
    // restarts the render immediately and never commits the stale one.
    setLastKey(key)
    setValue(source)
  }

  return [value, setValue]
}

/**
 * A page number that goes back to the first page whenever the filter changes.
 *
 * Four list screens had the same effect, and three of them called `setPage`
 * several lines *above* the `useState` that declares it -- legal, because a
 * `const` in a hook body is hoisted into the closure, and exactly the kind of
 * thing that stops being legal when somebody reorders the file.
 *
 * The behaviour matters: staying on page 3 while a search narrows the results
 * to two rows shows an empty table, which reads as "no matches" for a query
 * that matched.
 *
 * @param filter every filter that should reset the page, as one primitive.
 *               Join several with a separator rather than passing an object.
 */
export function usePagedFilter(filter: string): [number, Dispatch<SetStateAction<number>>] {
  const [page, setPage] = useState(0)
  const [lastFilter, setLastFilter] = useState(filter)

  if (lastFilter !== filter) {
    setLastFilter(filter)
    setPage(0)
  }

  return [page, setPage]
}
