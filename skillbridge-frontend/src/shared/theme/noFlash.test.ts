import { readFileSync } from 'node:fs'
import { resolve as resolvePath } from 'node:path'

import { afterEach, describe, expect, it, vi } from 'vitest'

import { readStoredPreference, resolve, THEME_STORAGE_KEY } from './theme'

/**
 * The inline script in `index.html` must decide the same thing the application
 * does.
 *
 * It exists because React applies the theme in an effect, which runs after the
 * first paint: without it, somebody who prefers dark sees a white page for a
 * frame on every single load. The cost of that fix is a second copy of the
 * decision, in a different language, that no bundler or type checker looks at.
 * Copies drift -- rename the storage key on one side and the page flashes
 * again, silently, for ever.
 *
 * So this does not read the script and compare strings. It **runs** it, in
 * jsdom, across the states that matter, and checks the class it leaves on
 * `<html>` against what `resolve(readStoredPreference())` would have said.
 */

const HTML = readFileSync(resolvePath(__dirname, '../../../index.html'), 'utf8')

/** The bare-JavaScript copy of the decision, lifted out of the document. */
function noFlashScript(): string {
  const match = HTML.match(/<script>([\s\S]*?)<\/script>/)
  if (!match) {
    throw new Error('index.html no longer carries an inline theme script')
  }
  return match[1]
}

function stubMatchMedia(prefersDark: boolean | 'absent') {
  if (prefersDark === 'absent') {
    // Old browsers, and some embedded webviews. The script must not throw.
    vi.stubGlobal('matchMedia', undefined)
    return
  }
  vi.stubGlobal('matchMedia', (query: string) => ({
    media: query,
    matches: query.includes('dark') ? prefersDark : !prefersDark,
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
}

function runScript() {
  document.documentElement.classList.remove('dark')
  document.documentElement.style.colorScheme = ''
  new Function(noFlashScript())()
  return document.documentElement.classList.contains('dark')
}

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
  document.documentElement.classList.remove('dark')
  document.documentElement.style.colorScheme = ''
})

describe('the pre-paint theme script', () => {
  const CASES: Array<{ stored: string | null; system: boolean; expected: boolean }> = [
    { stored: null, system: false, expected: false },
    { stored: null, system: true, expected: true },
    { stored: 'dark', system: false, expected: true },
    { stored: 'dark', system: true, expected: true },
    { stored: 'light', system: true, expected: false },
    { stored: 'light', system: false, expected: false },
    // Anything else in the key is not a preference; the system decides.
    { stored: 'midnight', system: true, expected: true },
    { stored: 'midnight', system: false, expected: false },
  ]

  it.each(CASES)(
    'agrees with the application for stored=$stored, system dark=$system',
    ({ stored, system, expected }) => {
      if (stored !== null) {
        localStorage.setItem(THEME_STORAGE_KEY, stored)
      }
      stubMatchMedia(system)

      const fromScript = runScript()

      expect(fromScript).toBe(expected)
      // The real point: not that it matches a table, but that it matches the
      // module the rest of the application uses.
      expect(fromScript).toBe(resolve(readStoredPreference()) === 'dark')
    },
  )

  it('sets color-scheme too, so the browser furniture matches', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    stubMatchMedia(false)

    runScript()

    expect(document.documentElement.style.colorScheme).toBe('dark')
  })

  it('uses the same storage key the application writes', () => {
    expect(noFlashScript()).toContain(`'${THEME_STORAGE_KEY}'`)
  })

  it('survives a browser with no matchMedia', () => {
    stubMatchMedia('absent')

    // Throwing here would abort the inline script and, because it is in <head>,
    // leave the page with no theme applied at all.
    expect(() => runScript()).not.toThrow()
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('survives storage that throws, as a private window does', () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('The operation is insecure.', 'SecurityError')
    })
    stubMatchMedia(true)

    expect(() => runScript()).not.toThrow()

    getItem.mockRestore()
  })
})
