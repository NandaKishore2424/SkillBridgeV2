import { renderHook, act } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { idempotencyHeaders, useIdempotencyKey } from './idempotency'

/**
 * The idempotency key identifies an *intent*, not a request.
 *
 * `POST /admin/batches`, `/admin/companies` and `/students/me/projects` have no
 * unique constraint of any kind behind them, so the key is the only thing
 * standing between a double-clicked submit and two identical rows that nothing
 * can tell apart.
 *
 * That makes "the key is stable" the whole contract, and it is the sort of
 * property that is easy to break without noticing: moving the `useRef` call
 * into the submit handler, or replacing it with `useState` plus a setter, or
 * generating the key inside the api function, all still compile and still
 * "work" in manual testing — one click, one row. The duplicate only appears
 * under a double click or a retry, which is exactly when nobody is watching.
 */
describe('useIdempotencyKey', () => {
  it('returns the same key for every call within one mount', () => {
    const { result } = renderHook(() => useIdempotencyKey())

    const first = result.current.current()
    const second = result.current.current()
    const third = result.current.current()

    expect(first).toBe(second)
    expect(second).toBe(third)
  })

  it('keeps the key across re-renders', () => {
    const { result, rerender } = renderHook(() => useIdempotencyKey())
    const before = result.current.current()

    rerender()
    rerender()

    expect(result.current.current()).toBe(before)
  })

  it('mints a different key for a different form', () => {
    const formA = renderHook(() => useIdempotencyKey())
    const formB = renderHook(() => useIdempotencyKey())

    expect(formA.result.current.current()).not.toBe(formB.result.current.current())
  })

  it('issues a new key only after reset, which is what makes the next submit a new operation', () => {
    const { result } = renderHook(() => useIdempotencyKey())
    const firstSubmit = result.current.current()

    act(() => result.current.reset())

    const secondSubmit = result.current.current()
    expect(secondSubmit).not.toBe(firstSubmit)
    // ...and is itself stable, so a retry of the second submit is still deduplicated.
    expect(result.current.current()).toBe(secondSubmit)
  })

  it('survives a missing crypto.randomUUID, which a plain-http host has', () => {
    // randomUUID needs a secure context. localhost counts; a plain-http staging
    // host does not, and there the fallback is the only thing between the form
    // and a TypeError on mount.
    const realCrypto = globalThis.crypto
    Object.defineProperty(globalThis, 'crypto', { value: {}, configurable: true })
    try {
      const { result } = renderHook(() => useIdempotencyKey())
      const key = result.current.current()

      expect(key).toMatch(/\S/)
      expect(result.current.current()).toBe(key)
    } finally {
      Object.defineProperty(globalThis, 'crypto', { value: realCrypto, configurable: true })
    }
  })
})

describe('idempotencyHeaders', () => {
  it('sends the key under the header name the backend filter reads', () => {
    // The backend's @Idempotent filter looks for exactly this name; a rename
    // here makes every guarded endpoint reject with 400 and the cause is a
    // long way from the symptom.
    expect(idempotencyHeaders('abc-123')).toEqual({
      headers: { 'Idempotency-Key': 'abc-123' },
    })
  })
})
