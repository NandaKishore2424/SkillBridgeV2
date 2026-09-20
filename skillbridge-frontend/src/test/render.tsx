import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, type RenderResult } from '@testing-library/react'
import type { ReactElement, ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'

import { setAccessToken } from '@/shared/auth/accessTokenStore'
import { AuthProvider } from '@/shared/contexts/AuthContext'
import { ThemeProvider } from '@/shared/theme'

import { userPayload } from './handlers'

const USER_KEY = 'skillbridge_user'

/**
 * Signs a user in the way the application does: by writing the keys
 * `AuthProvider` reads on mount.
 *
 * A non-JWT access token is deliberate. `isTokenExpired` treats a non-JWT as
 * valid ("the backend will validate"), so initialisation sets state without any
 * network call — which keeps a test's request assertions counting only what the
 * test itself caused.
 */
export function signIn(overrides: Record<string, unknown> = {}) {
  // In memory, where the application keeps it (shared/auth/accessTokenStore).
  setAccessToken('access-1')
  localStorage.setItem(USER_KEY, JSON.stringify(userPayload(overrides)))
}

/**
 * A QueryClient per test.
 *
 * `retry: false` matters: the application retries once, so a test asserting an
 * error state would otherwise wait for a second request and a backoff, and a
 * test asserting "exactly one request" would count two. `gcTime: 0` stops one
 * test's cached page satisfying the next test's query and making its request
 * assertion silently vacuous.
 */
function testQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  })
}

interface Options {
  /** Initial router entry, for a component that reads route params. */
  route?: string
}

/**
 * Renders a page with the providers it actually runs under: router, query
 * client and auth.
 *
 * Everything below the network is real. Only HTTP is intercepted, by MSW, so
 * the axios interceptors, the query cache and the role guard all behave as they
 * do in the browser.
 */
export function renderWithProviders(ui: ReactElement, { route = '/' }: Options = {}): RenderResult {
  const client = testQueryClient()

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <MemoryRouter initialEntries={[route]}>
        <ThemeProvider>
          <QueryClientProvider client={client}>
            <AuthProvider>{children}</AuthProvider>
          </QueryClientProvider>
        </ThemeProvider>
      </MemoryRouter>
    )
  }

  return render(ui, { wrapper: Wrapper })
}
