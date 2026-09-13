import { renderHook, act } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useDebouncedValue } from './useDebouncedValue'

/**
 * The search boxes on the admin lists filter server-side, so this hook is what
 * stands between a typing user and one request per keystroke.
 *
 * The distinction it has to get right is debounce versus throttle: the timer is
 * cleared on every change, so it fires only once the value has been *still*.
 * A version that forgot the cleanup would still look correct in the browser --
 * the final value does arrive -- while firing a request for every intermediate
 * one, and those can resolve out of order and leave the list showing results
 * for "datab".
 */
describe('useDebouncedValue', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('returns the initial value immediately, so the first render is not empty', () => {
    const { result } = renderHook(() => useDebouncedValue('database'))
    expect(result.current).toBe('database')
  })

  it('withholds a new value until the delay has passed', () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value), {
      initialProps: { value: 'a' },
    })

    rerender({ value: 'ab' })
    expect(result.current).toBe('a')

    act(() => { vi.advanceTimersByTime(299) })
    expect(result.current).toBe('a')

    act(() => { vi.advanceTimersByTime(1) })
    expect(result.current).toBe('ab')
  })

  it('emits once for a burst of keystrokes, not once per keystroke', () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value), {
      initialProps: { value: '' },
    })

    // "database", typed at 50ms per character: well inside the 300ms window.
    for (const value of ['d', 'da', 'dat', 'data', 'datab', 'databa', 'databas', 'database']) {
      rerender({ value })
      act(() => { vi.advanceTimersByTime(50) })
    }

    // Nothing has settled yet: every keystroke cleared the previous timer.
    expect(result.current).toBe('')

    act(() => { vi.advanceTimersByTime(300) })

    // And the one value that lands is the last one, never an intermediate.
    expect(result.current).toBe('database')
  })

  it('honours a custom delay', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebouncedValue(value, 1000),
      { initialProps: { value: 'x' } },
    )

    rerender({ value: 'y' })
    act(() => { vi.advanceTimersByTime(999) })
    expect(result.current).toBe('x')

    act(() => { vi.advanceTimersByTime(1) })
    expect(result.current).toBe('y')
  })

  it('does not emit a value the user has already replaced', () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value), {
      initialProps: { value: 'rust' },
    })

    act(() => { vi.advanceTimersByTime(300) })
    expect(result.current).toBe('rust')

    // Type something, then clear it before the timer fires. The intermediate
    // must never surface -- a request for it would come back after the one for
    // the cleared box and repopulate the list.
    rerender({ value: 'rustl' })
    act(() => { vi.advanceTimersByTime(100) })
    rerender({ value: '' })
    act(() => { vi.advanceTimersByTime(300) })

    expect(result.current).toBe('')
  })
})
