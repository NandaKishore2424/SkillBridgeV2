import { render, screen, waitFor } from '@testing-library/react'
import { delay, http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it } from 'vitest'

import apiClient from '@/api/client'
import { AuthProvider } from '@/shared/contexts/AuthContext'
import { useAuth } from '@/shared/hooks/useAuth'
import { getAccessToken, setAccessToken } from '@/shared/auth/accessTokenStore'

import { API, authPayload, userPayload } from '@/test/handlers'
import { server } from '@/test/server'

/**
 * Session renewal against a model of the real server.
 *
 * **What the server actually does** (backend `AuthenticationFailureStatusTest`,
 * measured 2026-09-17 and fixed 2026-09-19):
 * - an expired or missing access token gets **401** with
 *   `WWW-Authenticate: Bearer error="invalid_token"`;
 * - "you may not" is **403**, and is final;
 * - the refresh token exists **only in an HttpOnly cookie**: never in a
 *   response body, never in localStorage, never in a request body;
 * - since 2026-09-20 the **access** token is not in localStorage either: it
 *   lives in memory (shared/auth/accessTokenStore), so these tests seed and read
 *   it there, and assert that storage stays empty of it;
 * - each refresh **rotates** that cookie, and presenting the old one fails.
 *
 * The previous version of this file mocked a 401 "the way it would in
 * production" while production sent 403, and it seeded a refresh token into
 * localStorage that a real login never wrote. Both premises were false, so it
 * stayed green while sessions died fifteen minutes after login. Every premise
 * here is taken from the backend's own test.
 *
 * **The cookie is modelled as server-side state.** A browser attaches it to
 * each request automatically, so `browserCookie` is "what the browser would
 * send". A handler reads it on arrival, then pauses before rotating it. That
 * reproduces the real race: requests sent together all carry the same cookie,
 * because none has seen the rotation yet.
 */

const ACCESS_TOKEN_KEY = 'skillbridge_access_token'
const LEGACY_REFRESH_TOKEN_KEY = 'skillbridge_refresh_token'
const USER_KEY = 'skillbridge_user'

/** Refresh tokens the server still accepts. Rotation mutates this. */
let liveRefreshTokens: Set<string>
/** What the browser holds in its HttpOnly cookie jar. */
let browserCookie: string | null
let refreshCalls: number
let refreshBodies: unknown[]

function armServer() {
  liveRefreshTokens = new Set(['refresh-1'])
  browserCookie = 'refresh-1'
  refreshCalls = 0
  refreshBodies = []

  server.use(
    http.post(`${API}/auth/refresh`, async ({ request }) => {
      refreshCalls += 1
      refreshBodies.push(await request.json())
      const presented = browserCookie
      // Requests sent at the same moment all carry the cookie as it was then.
      await delay(20)

      if (!presented || !liveRefreshTokens.has(presented)) {
        return HttpResponse.json({ status: 401, error: 'UNAUTHORIZED' }, { status: 401 })
      }
      liveRefreshTokens.delete(presented)
      const next = `refresh-${refreshCalls + 1}`
      liveRefreshTokens.add(next)
      browserCookie = next
      return HttpResponse.json(authPayload('access-2'))
    }),

    // A protected read that accepts only the renewed access token, and answers a
    // stale one exactly as the server does.
    http.get(`${API}/admin/colleges`, ({ request }) => {
      if (request.headers.get('Authorization') !== 'Bearer access-2') {
        return HttpResponse.json(
          { status: 401, error: 'UNAUTHORIZED' },
          { status: 401, headers: { 'WWW-Authenticate': 'Bearer error="invalid_token"' } },
        )
      }
      return HttpResponse.json({
        items: [], page: 0, size: 20, totalElements: 0,
        totalPages: 0, first: true, last: true, sort: '',
      })
    }),
  )
}

function Probe() {
  const { isLoading } = useAuth()
  return <div data-testid="auth">{isLoading ? 'loading' : 'ready'}</div>
}

async function mountProvider() {
  render(
    <MemoryRouter>
      <AuthProvider>
        <Probe />
      </AuthProvider>
    </MemoryRouter>,
  )
  // The 401 interceptor is installed in an effect; requests fired before it is
  // registered would bypass the very thing under test.
  await waitFor(() => expect(screen.getByTestId('auth')).toHaveTextContent('ready'))
}

beforeEach(() => {
  // What a real login leaves behind: the access token and the user. Nothing
  // else. A non-JWT access token counts as unexpired on mount, so mounting makes
  // no network call and refreshCalls counts only what each test causes.
  setAccessToken('access-1')
  localStorage.setItem(USER_KEY, JSON.stringify(userPayload()))
  armServer()
})

describe('session renewal through the refresh cookie', () => {
  it('renews on a 401 with no refresh token anywhere in script-readable storage', async () => {
    await mountProvider()
    expect(localStorage.getItem(LEGACY_REFRESH_TOKEN_KEY)).toBeNull()

    const response = await apiClient.get('/admin/colleges')

    expect(response.status).toBe(200)
    expect(refreshCalls).toBe(1)
    // The cookie carries the token; the body must not.
    expect(refreshBodies).toEqual([{}])
    expect(getAccessToken()).toBe('access-2')
    // The point of the 2026-09-20 change: a renewed token is in memory and
    // nowhere a script can read it after the fact.
    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull()
    expect(localStorage.getItem(LEGACY_REFRESH_TOKEN_KEY)).toBeNull()
  })

  it('refreshes once for five simultaneous 401s, and every request still succeeds', async () => {
    await mountProvider()

    const responses = await Promise.all([
      apiClient.get('/admin/colleges'),
      apiClient.get('/admin/colleges'),
      apiClient.get('/admin/colleges'),
      apiClient.get('/admin/colleges'),
      apiClient.get('/admin/colleges'),
    ])

    expect(refreshCalls).toBe(1)
    expect(responses.map((r) => r.status)).toEqual([200, 200, 200, 200, 200])
    // The server's view agrees: one rotation, from the original cookie.
    expect(liveRefreshTokens.has('refresh-1')).toBe(false)
    expect(browserCookie).toBe('refresh-2')
  })

  it('does not log the user out when several requests race', async () => {
    await mountProvider()

    await Promise.all([
      apiClient.get('/admin/colleges'),
      apiClient.get('/admin/colleges'),
      apiClient.get('/admin/colleges'),
      apiClient.get('/admin/colleges'),
    ])

    // clearAuthState() empties these keys; their survival is the assertion
    // that the user is still signed in.
    expect(getAccessToken()).not.toBeNull()
    expect(localStorage.getItem(USER_KEY)).not.toBeNull()
  })

  it('treats a 403 as final: no refresh, the request fails', async () => {
    server.use(
      http.get(`${API}/admin/colleges`, () =>
        HttpResponse.json({ status: 403, error: 'FORBIDDEN' }, { status: 403 })),
    )
    await mountProvider()

    await expect(apiClient.get('/admin/colleges')).rejects.toMatchObject({
      response: { status: 403 },
    })
    expect(refreshCalls).toBe(0)
    expect(getAccessToken()).toBe('access-1')
  })

  it('retries a 401 exactly once, so an endpoint that always 401s cannot loop', async () => {
    server.use(
      http.get(`${API}/admin/colleges`, () =>
        HttpResponse.json({ status: 401, error: 'UNAUTHORIZED' }, { status: 401 })),
    )
    await mountProvider()

    await expect(apiClient.get('/admin/colleges')).rejects.toMatchObject({
      response: { status: 401 },
    })
    expect(refreshCalls).toBe(1)
  })

  it('adopts the token another tab just announced instead of logging out, when that tab won the refresh', async () => {
    server.use(
      http.post(`${API}/auth/refresh`, async () => {
        refreshCalls += 1
        // Another tab rotated the shared cookie first, so ours is refused.
        // That tab announced its fresh access token, and this tab applied it --
        // which is what the BroadcastChannel listener does, called directly here
        // because each test runs in one window.
        setAccessToken('access-2', false)
        return HttpResponse.json({ status: 401, error: 'UNAUTHORIZED' }, { status: 401 })
      }),
    )
    await mountProvider()

    const response = await apiClient.get('/admin/colleges')

    expect(response.status).toBe(200)
    expect(refreshCalls).toBe(1)
    expect(localStorage.getItem(USER_KEY)).not.toBeNull()
  })

  it('ends the session when the refresh cookie itself is rejected, without looping', async () => {
    browserCookie = 'revoked-elsewhere'
    await mountProvider()

    await expect(apiClient.get('/admin/colleges')).rejects.toBeDefined()

    expect(refreshCalls).toBe(1)
    expect(getAccessToken()).toBeNull()
    expect(localStorage.getItem(USER_KEY)).toBeNull()
  })
})
