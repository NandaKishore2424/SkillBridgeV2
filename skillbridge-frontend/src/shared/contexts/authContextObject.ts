import { createContext } from 'react'

import type { AuthContextValue } from '@/shared/types/auth'

/**
 * Its own module so that `AuthContext.tsx` exports nothing but `AuthProvider`.
 *
 * Vite's fast refresh gives up on a file that exports both a component and
 * something else, so editing the provider -- the single largest piece of state
 * in this application -- did a full page reload and threw away whatever screen
 * was open, on every save.
 *
 * `undefined` by default, so `useAuth` can tell "no provider" from "signed
 * out". A default object would make every consumer render as a signed-out user
 * with no error, which is the same bug as a silent redirect home.
 */
export const AuthContext = createContext<AuthContextValue | undefined>(undefined)
