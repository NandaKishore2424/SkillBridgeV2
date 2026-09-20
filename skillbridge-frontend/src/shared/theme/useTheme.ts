import { useContext } from 'react'

import { ThemeContext, type ThemeContextValue } from './themeContext'

/**
 * Throws outside a `ThemeProvider` rather than returning a default.
 *
 * A silent default is the failure mode that matters here: the toggle renders,
 * responds to clicks, and changes nothing, because it is writing to a context
 * nobody is reading.
 */
export function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext)
  if (!context) {
    throw new Error('useTheme must be used inside a ThemeProvider')
  }
  return context
}
