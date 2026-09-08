import { useEffect, useState } from 'react'

/**
 * Returns `value` after it has stopped changing for `delayMs`.
 *
 * Search boxes are wired to server-side filtering, and without this every
 * keystroke is a request: typing "database" is eight queries, seven of them
 * already stale by the time they land. Worse, they can resolve out of order,
 * so the list can settle on the results for "datab".
 *
 * 300ms is the usual compromise — below roughly 200ms it stops batching a
 * normal typing cadence, above roughly 400ms the list feels detached from the
 * box.
 */
export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs)
    // Clearing on every change is what makes this a debounce rather than a
    // throttle: the timer only fires once the value has been still.
    return () => clearTimeout(timer)
  }, [value, delayMs])

  return debounced
}
