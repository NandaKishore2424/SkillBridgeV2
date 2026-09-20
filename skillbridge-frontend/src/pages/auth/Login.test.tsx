import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { API } from '@/test/handlers'
import { renderWithProviders } from '@/test/render'
import { server } from '@/test/server'

import { Login } from './Login'

/**
 * Sign in.
 *
 * What is worth checking is the error path. Login failures are uniform by
 * design -- the backend never reveals whether it was the address or the
 * password -- but three of them say something the person can act on: a rate
 * limit, an expired invitation, and a deactivated account. A page that renders
 * its own "Login failed. Please try again." over the top of those is worse than
 * one with no message at all, because it hides the answer.
 */

async function signInWith(email: string, password: string) {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText(/email/i), email)
  await user.type(screen.getByLabelText(/password/i), password)
  await user.click(screen.getByRole('button', { name: /^sign in$/i }))
}

describe('the login page', () => {
  it('sends the credentials to the login endpoint', async () => {
    let sent: unknown
    server.use(
      http.post(`${API}/auth/login`, async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json({
          accessToken: 'access-1',
          expiresIn: 900,
          user: {
            id: 1,
            email: 'admin@college.invalid',
            role: 'COLLEGE_ADMIN',
            collegeId: 1,
            isActive: true,
            accountStatus: 'ACTIVE',
            profileCompleted: true,
            mustChangePassword: false,
          },
        })
      }),
    )

    renderWithProviders(<Login />)
    await signInWith('admin@college.invalid', 'correct horse battery staple')

    await waitFor(() =>
      expect(sent).toEqual({
        email: 'admin@college.invalid',
        password: 'correct horse battery staple',
      }),
    )
  })

  it('shows the server\'s message rather than a generic one', async () => {
    server.use(
      http.post(`${API}/auth/login`, () =>
        HttpResponse.json(
          {
            error: 'RATE_LIMITED',
            message: 'Too many attempts. Try again in a minute.',
            status: 429,
          },
          { status: 429 },
        )),
    )

    renderWithProviders(<Login />)
    await signInWith('admin@college.invalid', 'wrong')

    // "Login failed. Please try again." over the top of this would send the
    // person straight back into the rate limit they just hit.
    expect(await screen.findByText(/too many attempts/i)).toBeVisible()
  })

  it('falls back to its own words when the server sends none', async () => {
    server.use(http.post(`${API}/auth/login`, () => HttpResponse.error()))

    renderWithProviders(<Login />)
    await signInWith('admin@college.invalid', 'whatever')

    expect(await screen.findByRole('alert')).toHaveTextContent(/could not sign you in/i)
  })

  it('refuses an address that is not one, without asking the server', async () => {
    let asked = false
    server.use(
      http.post(`${API}/auth/login`, () => {
        asked = true
        return new HttpResponse(null, { status: 401 })
      }),
    )

    renderWithProviders(<Login />)
    await signInWith('not-an-address', 'whatever')

    expect(await screen.findByText(/enter a valid email address/i)).toBeVisible()
    expect(asked).toBe(false)
  })

  it('ties each message to its field for a screen reader', async () => {
    renderWithProviders(<Login />)
    await signInWith('not-an-address', 'whatever')

    const email = screen.getByLabelText(/email/i)
    await waitFor(() => expect(email).toHaveAttribute('aria-invalid', 'true'))
    // Without `aria-describedby` the message is visible and unannounced: the
    // field just refuses to submit and says nothing.
    expect(email).toHaveAccessibleDescription(/enter a valid email address/i)
  })

  it('is usable before the session check has come back', async () => {
    // AuthProvider tries a refresh on mount. It never succeeds for a visitor
    // who is here to sign in, and while it was in flight this page disabled
    // both fields and labelled the button "Signing in..." -- on every load.
    server.use(
      http.post(`${API}/auth/refresh`, async () => {
        await new Promise((resolve) => setTimeout(resolve, 5_000))
        return new HttpResponse(null, { status: 401 })
      }),
    )

    renderWithProviders(<Login />)

    expect(screen.getByLabelText(/email/i)).toBeEnabled()
    expect(screen.getByLabelText(/password/i)).toBeEnabled()
    expect(screen.getByRole('button', { name: /^sign in$/i })).toBeEnabled()
  })

  it('offers no route that does not exist', () => {
    renderWithProviders(<Login />)

    // There is no password-reset flow and no sign-up. Both links were removed
    // 2026-09-06 because they led to the catch-all.
    expect(screen.queryByRole('link', { name: /forgot/i })).toBeNull()
    expect(screen.queryByRole('link', { name: /sign up|register/i })).toBeNull()
    expect(screen.getByRole('link', { name: /back/i })).toHaveAttribute('href', '/')
  })
})
