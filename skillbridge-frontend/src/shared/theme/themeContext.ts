import { createContext } from 'react'

import type { ResolvedTheme, ThemePreference } from './theme'

export interface ThemeContextValue {
  /** What the person chose, including `system` for "no choice". */
  preference: ThemePreference
  /** What is on screen right now. */
  theme: ResolvedTheme
  setPreference: (preference: ThemePreference) => void
  /** Light to dark and back, which is what a two-state control would do. */
  toggle: () => void
}

/**
 * Its own module so that `ThemeProvider.tsx` exports nothing but a component.
 *
 * Not a style preference: Vite's fast refresh gives up on a file that exports
 * both a component and something else, so editing the provider during
 * development would do a full reload and lose the state of whatever screen was
 * open. `undefined` by default, so `useTheme` can tell "no provider" from
 * "light".
 */
export const ThemeContext = createContext<ThemeContextValue | undefined>(undefined)
