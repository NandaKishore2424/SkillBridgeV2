import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react'

import { ThemeContext } from './themeContext'
import {
  applyTheme,
  readStoredPreference,
  storePreference,
  type ResolvedTheme,
  type ThemePreference,
} from './theme'

const DARK_QUERY = '(prefers-color-scheme: dark)'

/**
 * Subscribes to the operating system's setting.
 *
 * `useSyncExternalStore` rather than `useEffect` + `setState`: the media query
 * is an external store, and reading it through an effect means the first render
 * shows the wrong theme and a second render corrects it -- a visible flash, and
 * a cascading render React now warns about.
 */
function subscribeToSystem(onChange: () => void): () => void {
  if (typeof matchMedia !== 'function') {
    return () => {}
  }
  const query = matchMedia(DARK_QUERY)
  query.addEventListener('change', onChange)
  return () => query.removeEventListener('change', onChange)
}

function systemSnapshot(): boolean {
  return typeof matchMedia === 'function' && matchMedia(DARK_QUERY).matches
}

/**
 * Owns the theme for the whole application.
 *
 * The class is applied here **and** by an inline script in `index.html`. Both
 * are needed: the script runs before first paint so the page never flashes
 * white on the way to dark, and this keeps the class in step once React is
 * running. `noFlash.test.ts` runs the script and checks the two agree.
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [preference, setPreferenceState] = useState<ThemePreference>(readStoredPreference)
  const systemPrefersDark = useSyncExternalStore(subscribeToSystem, systemSnapshot, () => false)

  // Derived, not stored. A second copy in state is a second thing that can be
  // stale, and the only way to fill it is the effect this avoids.
  const theme: ResolvedTheme =
    preference === 'system' ? (systemPrefersDark ? 'dark' : 'light') : preference

  useEffect(() => {
    // Writing to the DOM is what an effect is for; nothing here sets state.
    applyTheme(theme)
  }, [theme])

  const setPreference = useCallback((next: ThemePreference) => {
    storePreference(next)
    setPreferenceState(next)
  }, [])

  const toggle = useCallback(() => {
    // Resolves first, so the control always does the visible thing: whatever is
    // on screen, show the other one.
    setPreference(theme === 'dark' ? 'light' : 'dark')
  }, [setPreference, theme])

  const value = useMemo(
    () => ({ preference, theme, setPreference, toggle }),
    [preference, theme, setPreference, toggle],
  )

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}
