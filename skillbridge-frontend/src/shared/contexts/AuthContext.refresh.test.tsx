import { render, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it } from 'vitest'

import apiClient from '@/api/client'
import { AuthProvider } from '@/shared/contexts/AuthContext'
import { useAuth } from '@/shared/hooks/useAuth'

import { API, authPayload, userPayload } from '@/test/handlers'
import { server } from '@/test/server'

/**
 * Concurrent 401s must produce exactly one refresh.
 *
 * This guards a bug that reached users: every dashboard here fires several
 * requests at once, so when the access token expires they all 401 together.
 * Without a single-flight guard each one calls `/auth/refresh` with the *same*
 * stored refresh token. The server rotates refresh tokens — a successful
 * refresh revokes the one it was given — so the first call succeeds and the
 * rest are rejected as invalid, and the rejection handler logs the user out.
 *
 * It used to be survivable at one logout an hour. The access-token TTL is now
 * fifteen minutes, because the token is authoritative for authorisation and its
 * lifetime is the revocation window, so the race is four times as frequent.
 *
 * **The rotation is modelled here rather than stubbed away.** A handler that
 * happily answered every refresh would let the broken implementation pass: it
 * would make five calls, all succeed, and nothing would be visibly wrong. The
 * handler below revokes the token it consumes, exactly as the server does, so
 * removing the guard reproduces the production failure instead of hiding it.
 */

const ACCESS_TOKEN_KEY = 'skillbridge_access_token'
const REFRESH_TOKEN_KEY = 'skillbridge_refresh_token'
const USER_KEY = 'skillbridge_user'

/** Refresh tokens the server still considers valid. Rotation mutates this. */
let liveRefreshTokens: Set<string>
let refreshCalls: number

function armServer() {
  liveRefreshTokens = new Set(['refresh-1'])
  refreshCalls = 0

  server.use(
    http.post(`${API}/auth/refresh`, async ({ request }) => {
      refreshCalls += 1
      const body = (await request.json()) as { refreshToken?: string } | null
      const presented = body?.refreshToken

      if (!presented || !liveRefreshTokens.has(presented)) {
        // Exactly what the server does with an already-rotated token.
        return HttpResponse.json({ error: 'INVALID_REFRESH_TOKEN' }, { status: 401 })
      }

      liveRefreshTokens.delete(presented)
      liveRefreshTokens.add('refresh-2')
      return HttpResponse.json(authPayload('access-2', 'refresh-2'))
    }),

    // A protected read that only accepts the *new* access token, so every
    // request made with the stale one 401s the way it would in production.
    http.get(`${API}/admin/colleges`, ({ request }) => {
      if (request.headers.get('Authorization') !== 'Bearer access-2') {
        return new HttpResponse(null, { status: 401 })
      }
      return HttpResponse.json({
        items: [], page: 0, size: 20, totalElements: 0,
        totalPages: 0, first: true, last: true, sort: '',
      })
    }),
  )
}

/** Renders the provider and resolves once its mount effect has settled. */
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
  // A non-JWT access token is treated as valid by the provider's mount check,
  // so initialisation sets state without any network call. That keeps
  // refreshCalls counting only what the test causes.
  localStorage.setItem(ACCESS_TOKEN_KEY, 'access-1')
  localStorage.setItem(REFRESH_TOKEN_KEY, 'refresh-1')
  localStorage.setItem(USER_KEY, JSON.stringify(userPayload()))
  armServer()
})

describe('token refresh under concurrent 401s', () => {
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
  })

  it('leaves the rotated token in storage, so the next refresh has something valid to present', async () => {
    await mountProvider()

    await Promise.all([
      apiClient.get('/admin/colleges'),
      apiClient.get('/admin/colleges'),
      apiClient.get('/admin/colleges'),
    ])

    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBe('access-2')
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('refresh-2')
    // The server's view agrees: the presented token was consumed, the new one lives.
    expect(liveRefreshTokens.has('refresh-1')).toBe(false)
    expect(liveRefreshTokens.has('refresh-2')).toBe(true)
  })

  it('does not log the user out when several requests race', async () => {
    await mountProvider()

    await Promise.all([
      apiClient.get('/admin/colleges'),
      apiClient.get('/admin/colleges'),
      apiClient.get('/admin/colleges'),
      apiClient.get('/admin/colleges'),
    ])

    // clearAuthState() empties all three keys. Their survival is the assertion
    // that the user is still signed in — which is the user-visible symptom the
    // whole guard exists to prevent.
    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).not.toBeNull()
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).not.toBeNull()
    expect(localStorage.getItem(USER_KEY)).not.toBeNull()
  })

  it('retries a 401 exactly once, so an endpoint that always 401s cannot loop', async () => {
    // A token that is genuinely rejected: refresh succeeds, the retry 401s
    // again, and `_retry` must stop it there rather than refreshing for ever.
    server.use(
      http.get(`${API}/admin/colleges`, () => new HttpResponse(null, { status: 401 })),
    )
    await mountProvider()

    await expect(apiClient.get('/admin/colleges')).rejects.toMatchObject({
      response: { status: 401 },
    })

    expect(refreshCalls).toBe(1)
  })
})
