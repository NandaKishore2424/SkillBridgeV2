/**
 * Which theme the page is showing, and why.
 *
 * Three states, not two. "Dark" and "light" are a choice the person made and we
 * keep; "system" is the absence of a choice, and it has to keep following the
 * operating system afterwards -- somebody whose laptop turns dark at sunset
 * expects this page to turn with it. Collapsing that to a boolean loses the
 * difference between "they want light" and "it is currently light", and the
 * page then stops following at the first toggle.
 */
export type ThemePreference = 'light' | 'dark' | 'system'

/** What is actually painted: `system` has been resolved away. */
export type ResolvedTheme = 'light' | 'dark'

export const THEME_STORAGE_KEY = 'skillbridge_theme'

const PREFERENCES: ThemePreference[] = ['light', 'dark', 'system']

export function isThemePreference(value: unknown): value is ThemePreference {
  return typeof value === 'string' && (PREFERENCES as string[]).includes(value)
}

/**
 * The stored preference, or `system` when there is none.
 *
 * Storage can throw outright -- a Safari private window, blocked site data --
 * so this never lets a theme lookup break the render.
 */
export function readStoredPreference(): ThemePreference {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY)
    return isThemePreference(stored) ? stored : 'system'
  } catch {
    return 'system'
  }
}

export function storePreference(preference: ThemePreference): void {
  try {
    if (preference === 'system') {
      // Removing rather than storing "system" keeps "never chose" and "chose to
      // follow the system" the same state, which is what they mean here.
      localStorage.removeItem(THEME_STORAGE_KEY)
    } else {
      localStorage.setItem(THEME_STORAGE_KEY, preference)
    }
  } catch {
    // A theme that does not survive a reload is a worse page, not a broken one.
  }
}

export function systemPrefersDark(): boolean {
  return typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches
}

export function resolve(preference: ThemePreference): ResolvedTheme {
  if (preference === 'system') {
    return systemPrefersDark() ? 'dark' : 'light'
  }
  return preference
}

/**
 * Puts the resolved theme on `<html>`, where the `.dark` token block hangs.
 *
 * `color-scheme` goes with it: without it the browser keeps painting form
 * controls, scrollbars and the page behind an overscroll in light colours, so a
 * dark page has white scrollbars and a white flash at the top of a bounce.
 */
export function applyTheme(theme: ResolvedTheme): void {
  const root = document.documentElement
  root.classList.toggle('dark', theme === 'dark')
  root.style.colorScheme = theme
}
