/**
 * Idempotency keys for the create endpoints that require one.
 *
 * The backend rejects `POST /admin/batches`, `POST /admin/companies` and
 * `POST /students/me/projects` without an `Idempotency-Key` header, because
 * none of those tables has a unique constraint: a duplicate request leaves two
 * identical rows with nothing to tell them apart.
 *
 * **A key generated per HTTP call would be useless here.** Two clicks would
 * produce two keys and two rows, which is the exact thing being prevented. The
 * key has to identify the *intent* — one filled-in form, however many times it
 * is sent — so `useIdempotencyKey` mints one when the form mounts and keeps it
 * until a submit succeeds.
 *
 * That gives the two cases that actually happen:
 *   - a double-clicked submit sends the same key twice, and the second is
 *     replayed or answered 409 rather than creating a second row;
 *   - a submit that fails and is retried reuses the key, so if the first
 *     attempt did reach the server the retry returns that result instead of
 *     duplicating it.
 */
import { useCallback, useRef } from 'react'

function newKey(): string {
  // randomUUID needs a secure context; http://localhost counts as one, but a
  // plain-http staging host would not, so there is a fallback.
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 14)}`
}

export interface IdempotencyKeyHandle {
  /** The key to send. Stable until `reset` is called. */
  current: () => string
  /** Call after a successful submit so the next one is a genuinely new operation. */
  reset: () => void
}

export function useIdempotencyKey(): IdempotencyKeyHandle {
  const keyRef = useRef<string>(newKey())
  const current = useCallback(() => keyRef.current, [])
  const reset = useCallback(() => {
    keyRef.current = newKey()
  }, [])
  return { current, reset }
}

/** Header object for an axios request. */
export function idempotencyHeaders(key: string) {
  return { headers: { 'Idempotency-Key': key } }
}
