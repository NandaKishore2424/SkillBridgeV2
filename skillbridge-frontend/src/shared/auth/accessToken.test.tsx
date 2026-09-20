import { render, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it } from 'vitest'

import { getAccessToken, setAccessToken } from '@/shared/auth/accessTokenStore'
import { AuthProvider } from '@/shared/contexts/AuthContext'
import { useAuth } from '@/shared/hooks/useAuth'

import { API, authPayload, userPayload } from '@/test/handlers'
import { server } from '@/test/server'

/**
 * The access token is never written where a script can read it later.
 *
 * It used to live in `localStorage`. One cross-site-scripting hole anywhere on
 * the page -- a dependency, a rendered field -- and the token is copied out and
 * replayed until it expires, from any machine. In memory it dies with the tab
 * and cannot be read after the fact.
 *
 * The refresh token was already out of reach, in an HttpOnly cookie; that is
 * what lets a reload restore the session with nothing kept in storage.
 */

const ACCESS_TOKEN_KEY = 'skillbridge_access_token'
const LEGACY_REFRESH_TOKEN_KEY = 'skillbridge_refresh_token'
const USER_KEY = 'skillbridge_user'

/** Everything the app wrote to storage, as a script on the page would see it. */
function scriptReadableStorage(): Record<string, string> {
  return Object.fromEntries(
    Object.keys(localStorage).map((key) => [key, localStorage.getItem(key) ?? '']),
  )
}

function Probe() {
  const { login, isLoading, isAuthenticated } = useAuth()
  return (
    <div>
      <div data-testid="auth">{isLoading ? 'loading' : isAuthenticated ? 'in' : 'out'}</div>
      <button onClick={() => void login({ email: 'a@example.invalid', password: 'x' })}>sign in</button>
    </div>
  )
}

async function mount() {
  render(
    <MemoryRouter>
      <AuthProvider>
        <Probe />
      </AuthProvider>
    </MemoryRouter>,
  )
  await waitFor(() => expect(screen.getByTestId('auth')).not.toHaveTextContent('loading'))
}

beforeEach(() => {
  server.use(
    http.post(`${API}/auth/login`, () => HttpResponse.json(authPayload('access-fresh'))),
    http.post(`${API}/auth/refresh`, () => HttpResponse.json(authPayload('access-renewed'))),
  )
})

describe('where the access token lives', () => {
  it('is in memory after a login, and in no storage key at all', async () => {
    await mount()

    screen.getByRole('button', { name: 'sign in' }).click()

    await waitFor(() => expect(getAccessToken()).toBe('access-fresh'))
    const storage = scriptReadableStorage()
    expect(storage).not.toHaveProperty(ACCESS_TOKEN_KEY)
    expect(Object.values(storage)).not.toContain('access-fresh')
    // The user is kept, so a reload can render before the refresh returns. It is
    // the person's own name and role, not a credential.
    expect(storage[USER_KEY]).toBeDefined()
  })

  it('a reload has no token and gets a new one from the refresh cookie', async () => {
    // A reload is exactly this: storage as the last session left it, memory empty.
    localStorage.setItem(USER_KEY, JSON.stringify(userPayload()))
    setAccessToken(null, false)

    await mount()

    await waitFor(() => expect(getAccessToken()).toBe('access-renewed'))
    expect(screen.getByTestId('auth')).toHaveTextContent('in')
    expect(scriptReadableStorage()).not.toHaveProperty(ACCESS_TOKEN_KEY)
  })

  it('deletes what an older build left in storage', async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'left-behind-by-an-older-build')
    localStorage.setItem(LEGACY_REFRESH_TOKEN_KEY, 'also-left-behind')
    localStorage.setItem(USER_KEY, JSON.stringify(userPayload()))
    setAccessToken(null, false)
    server.use(http.post(`${API}/auth/refresh`, () =>
      HttpResponse.json({ status: 401, error: 'UNAUTHORIZED' }, { status: 401 })))

    await mount()

    await waitFor(() => expect(scriptReadableStorage()).not.toHaveProperty(ACCESS_TOKEN_KEY))
    expect(scriptReadableStorage()).not.toHaveProperty(LEGACY_REFRESH_TOKEN_KEY)
  })
})
