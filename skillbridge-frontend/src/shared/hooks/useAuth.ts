import { useContext } from 'react'

import { AuthContext } from '@/shared/contexts/authContextObject'
import type { AuthContextValue } from '@/shared/types/auth'

/**
 * The signed-in user, and the actions that change who that is.
 *
 * It lives here rather than in `AuthContext.tsx` so that file exports only
 * `AuthProvider`: a module that exports a component *and* a hook defeats Vite's
 * fast refresh, and the provider is the one file whose reload costs the most --
 * it throws away the whole session's screen state on every save.
 *
 * Throws outside a provider rather than returning a signed-out default, which
 * would render every consumer as a logged-out user with nothing to say why.
 */
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}
