import { http, HttpResponse } from 'msw'

/**
 * Default handlers: the happy path for anything a test does not care about.
 *
 * A test that cares overrides with `server.use(...)`, which `resetHandlers()`
 * reverts afterwards.
 *
 * Paths are matched with a `*` prefix because the client's base URL comes from
 * `VITE_API_BASE_URL` and falls back to `http://localhost:8080/api/v1`. Pinning
 * the host here would make every test fail the day that variable is set in an
 * environment file.
 */
export const API = '*/api/v1'

/** A user payload shaped like the backend's, for handlers that return one. */
export function userPayload(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    email: 'college.admin@example.invalid',
    role: 'COLLEGE_ADMIN',
    collegeId: 1,
    isActive: true,
    accountStatus: 'ACTIVE',
    profileCompleted: true,
    mustChangePassword: false,
    ...overrides,
  }
}

/** An `AuthResponse`, as `/auth/login` and `/auth/refresh` both return. */
export function authPayload(accessToken: string, refreshToken: string) {
  return {
    accessToken,
    refreshToken,
    expiresIn: 900,
    user: userPayload(),
  }
}

export const handlers = [
  http.post(`${API}/auth/login`, () =>
    HttpResponse.json(authPayload('access-1', 'refresh-1'))),

  http.post(`${API}/auth/refresh`, () =>
    HttpResponse.json(authPayload('access-2', 'refresh-2'))),

  http.post(`${API}/auth/logout`, () => new HttpResponse(null, { status: 204 })),
]
