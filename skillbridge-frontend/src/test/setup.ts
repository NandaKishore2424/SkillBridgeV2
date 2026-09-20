import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'

import { clearAccessToken } from '@/shared/auth/accessTokenStore'

import { server } from './server'

/**
 * Runs before every test file.
 *
 * `onUnhandledRequest: 'error'` is the setting that earns MSW its place. The
 * default warns and lets the request through to a real network that does not
 * exist under jsdom, so a component calling an endpoint nobody stubbed fails
 * later, somewhere else, as a timeout. Erroring names the URL at the moment it
 * is requested.
 */
beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' })
})

afterEach(() => {
  cleanup()
  // Per-test overrides via server.use() are reverted here, so one test cannot
  // leave a 500 armed for the next.
  server.resetHandlers()
  localStorage.clear()
  // Module state, so it outlives a test unless cleared here.
  clearAccessToken(false)
  sessionStorage.clear()
})

afterAll(() => {
  server.close()
})
