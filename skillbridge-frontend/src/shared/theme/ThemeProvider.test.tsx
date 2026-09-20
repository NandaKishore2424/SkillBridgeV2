import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ThemeProvider } from './ThemeProvider'
import { ThemeToggle } from './ThemeToggle'
import { useTheme } from './useTheme'
import { THEME_STORAGE_KEY } from './theme'

/**
 * The theme, from the three angles that have actually gone wrong in themed
 * applications: the class never reaches `<html>`, the choice does not survive a
 * reload, and picking a theme once means the page stops following the system
 * for ever.
 *
 * jsdom has no `matchMedia`, so each test that cares installs one. That is not
 * a workaround -- it is the only way to say "this machine is set to dark" and
 * then change its mind mid-test.
 */

/** A `matchMedia` for `(prefers-color-scheme: dark)` whose answer can change. */
function installMatchMedia(prefersDark: boolean) {
  const listeners = new Set<(event: MediaQueryListEvent) => void>()
  let matches = prefersDark

  vi.stubGlobal('matchMedia', (query: string) => ({
    media: query,
    get matches() {
      return query.includes('dark') ? matches : !matches
    },
    addEventListener: (_: string, listener: (event: MediaQueryListEvent) => void) =>
      listeners.add(listener),
    removeEventListener: (_: string, listener: (event: MediaQueryListEvent) => void) =>
      listeners.delete(listener),
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }))

  return {
    /** The operating system switches, the way a sunset schedule does. */
    change(nowDark: boolean) {
      matches = nowDark
      listeners.forEach((listener) => listener({ matches: nowDark } as MediaQueryListEvent))
    },
    get listenerCount() {
      return listeners.size
    },
  }
}

function isDark() {
  return document.documentElement.classList.contains('dark')
}

function Probe() {
  const { preference, theme } = useTheme()
  return <div data-testid="state">{`${preference}/${theme}`}</div>
}

function mount() {
  return render(
    <ThemeProvider>
      <Probe />
      <ThemeToggle />
    </ThemeProvider>,
  )
}

async function choose(label: string) {
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: /change theme/i }))
  await user.click(await screen.findByRole('menuitem', { name: label }))
}

beforeEach(() => {
  installMatchMedia(false)
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.documentElement.classList.remove('dark')
  document.documentElement.style.colorScheme = ''
})

describe('the theme', () => {
  it('follows the system when nobody has chosen', async () => {
    installMatchMedia(true)

    mount()

    await waitFor(() => expect(isDark()).toBe(true))
    expect(screen.getByTestId('state')).toHaveTextContent('system/dark')
    // Nothing stored: "no choice" and "chose system" are the same state.
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull()
  })

  it('puts the class on <html>, where the token block hangs', async () => {
    mount()
    expect(isDark()).toBe(false)

    await choose('Dark')

    await waitFor(() => expect(isDark()).toBe(true))
    // Without this the browser keeps painting scrollbars and form controls
    // light, so a dark page has white furniture around it.
    expect(document.documentElement.style.colorScheme).toBe('dark')
  })

  it('remembers the choice across a reload', async () => {
    const first = mount()
    await choose('Dark')
    await waitFor(() => expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark'))

    // A reload is this: the DOM is thrown away, storage is not.
    first.unmount()
    document.documentElement.classList.remove('dark')
    mount()

    await waitFor(() => expect(isDark()).toBe(true))
    expect(screen.getByTestId('state')).toHaveTextContent('dark/dark')
  })

  it('keeps following the system after the person picks System again', async () => {
    const media = installMatchMedia(false)
    mount()

    await choose('Dark')
    await waitFor(() => expect(isDark()).toBe(true))
    await choose('System')
    await waitFor(() => expect(isDark()).toBe(false))

    media.change(true)

    await waitFor(() => expect(isDark()).toBe(true))
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull()
  })

  it('stops following the system once the person has picked one', async () => {
    const media = installMatchMedia(false)
    mount()

    await choose('Light')
    media.change(true)

    // The machine went dark; the person said light. The person wins.
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('light/light'))
    expect(isDark()).toBe(false)
  })

  it('lets go of the media query when it unmounts', async () => {
    const media = installMatchMedia(false)
    const view = mount()
    await waitFor(() => expect(media.listenerCount).toBe(1))

    view.unmount()

    // Left attached, this leaks one listener per mount, and each one keeps
    // reaching into a tree that is no longer on the page.
    expect(media.listenerCount).toBe(0)
  })

  it('tells assistive technology what is on screen, not what was chosen', async () => {
    installMatchMedia(true)
    mount()

    // The preference is "system"; announcing that says nothing about the page.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /change theme/i })).toHaveAccessibleName(
        'Theme: dark. Change theme',
      ),
    )
  })

  it('renders light and stays usable when storage throws', async () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('The operation is insecure.', 'SecurityError')
    })
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('The operation is insecure.', 'SecurityError')
    })

    mount()
    await choose('Dark')

    // A private window cannot remember the choice; it must still honour it now.
    await waitFor(() => expect(isDark()).toBe(true))
    getItem.mockRestore()
    setItem.mockRestore()
  })

  it('refuses to render the toggle outside a provider', () => {
    // A context default would make this render, click, and change nothing.
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() => render(<ThemeToggle />)).toThrow(/ThemeProvider/)
    quiet.mockRestore()
  })
})
