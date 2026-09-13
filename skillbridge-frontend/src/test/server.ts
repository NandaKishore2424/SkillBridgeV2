import { setupServer } from 'msw/node'

import { handlers } from './handlers'

/**
 * The MSW server every test shares.
 *
 * Intercepting at the network layer rather than mocking the api modules is the
 * whole point: the request still goes through axios, so the interceptors that
 * attach the bearer token, refresh on 401 and retry the original request are
 * all exercised. Mocking `authAPI` would skip precisely the code that has
 * actually broken here.
 */
export const server = setupServer(...handlers)
