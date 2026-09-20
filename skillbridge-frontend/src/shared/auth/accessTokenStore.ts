/**
 * The access token, in memory only.
 *
 * It used to live in `localStorage`, where any script on the page can read it:
 * one cross-site-scripting hole anywhere -- a dependency, a rendered field --
 * and the token is copied out and replayed from anywhere until it expires.
 * Memory is not immune to XSS, but it does not survive the tab, it is not
 * readable by another script after the fact, and it cannot be exfiltrated by a
 * payload that only reads storage.
 *
 * The refresh token is untouched by all this: it lives in an HttpOnly cookie
 * the browser will not show to script at all.
 *
 * **Why a BroadcastChannel.** Tabs share one cookie jar, so only one of them can
 * win a refresh; the loser used to read the winner's new token out of
 * `localStorage` and carry on. With the token in memory each tab has its own, so
 * the winner announces it here instead. The message never leaves the origin and
 * is never written to disk.
 */

const CHANNEL = 'skillbridge-access-token'

let accessToken: string | null = null

/** Null when nobody is signed in, or when the tab has just been reloaded. */
export function getAccessToken(): string | null {
  return accessToken
}

/**
 * Records a freshly issued token and tells the other tabs.
 *
 * @param announce false when applying a token another tab announced, so the
 *                 announcement does not bounce back and forth
 */
export function setAccessToken(token: string | null, announce = true): void {
  accessToken = token
  if (announce) {
    post({ type: 'token', token })
  }
}

export function clearAccessToken(announce = true): void {
  setAccessToken(null, announce)
}

/**
 * Calls `onToken` when another tab issues or clears a token.
 *
 * @returns a function that stops listening
 */
export function onTokenFromAnotherTab(onToken: (token: string | null) => void): () => void {
  const channel = open()
  if (!channel) {
    return () => {}
  }
  const listener = (event: MessageEvent) => {
    if (event.data?.type === 'token') {
      onToken(event.data.token ?? null)
    }
  }
  channel.addEventListener('message', listener)
  return () => {
    channel.removeEventListener('message', listener)
    channel.close()
  }
}

function post(message: { type: 'token'; token: string | null }): void {
  const channel = open()
  if (!channel) {
    return
  }
  try {
    channel.postMessage(message)
  } finally {
    channel.close()
  }
}

/** Absent in older browsers and in some test environments; the app works without it. */
function open(): BroadcastChannel | null {
  return typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(CHANNEL)
}
