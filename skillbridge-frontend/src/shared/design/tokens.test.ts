import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import { contrastRatio, hueDistance, parseHsl } from './colour'

/**
 * The rules the design tokens have to keep.
 *
 * A palette is the one part of a UI where a mistake is both invisible in review
 * and visible on every screen. Three failures are worth failing a build for:
 *
 * 1. **A token Tailwind names but the stylesheet never defines.** `bg-chart-1`
 *    then compiles to `hsl( / 1)`, which browsers drop, so the element keeps
 *    whatever colour it inherited and nothing errors. This repository shipped
 *    exactly that: `tailwind.config.js` declared `chart-1` to `chart-5` and
 *    `index.css` defined none of them.
 * 2. **Text that does not read on its own background.** Judged by eye on one
 *    monitor, a 3:1 pair looks fine; it is unreadable in sunlight and fails an
 *    audit.
 * 3. **A theme that defines a token in one mode and forgets it in the other**,
 *    which looks correct until somebody switches themes.
 *
 * WCAG 2.1 AA is the bar: 4.5:1 for body text, 3:1 for the boundary of a
 * control a person has to find (1.4.11).
 */

const CSS = readFileSync(resolve(__dirname, '../../index.css'), 'utf8')
const CONFIG = readFileSync(resolve(__dirname, '../../../tailwind.config.js'), 'utf8')

/** Every `--token: value` inside the given selector's block. */
function tokensIn(selector: string): Record<string, string> {
  const start = CSS.indexOf(`${selector} {`)
  if (start < 0) {
    throw new Error(`no "${selector}" block in index.css`)
  }
  // The block ends at the first line that closes it at this indentation. The
  // token blocks contain no nested braces, so the first `}` is the right one.
  const body = CSS.slice(start, CSS.indexOf('\n  }', start))
  return Object.fromEntries(
    [...body.matchAll(/--([a-z0-9-]+):\s*([^;]+);/g)].map(([, name, value]) => [name, value.trim()]),
  )
}

const LIGHT = tokensIn(':root')
const DARK = tokensIn('.dark')
const THEMES = { light: LIGHT, dark: DARK }

/** Colour tokens that Tailwind exposes as a class, read out of the real config. */
const NAMED_BY_TAILWIND = [...CONFIG.matchAll(/withAlpha\("([a-z0-9-]+)"\)/g)].map(([, t]) => t)
  .concat([...CONFIG.matchAll(/pair\("([a-z0-9-]+)"\)/g)].flatMap(([, t]) => [t, `${t}-foreground`]))

/**
 * Foreground/background pairs the application actually renders together.
 *
 * Listed rather than derived from the `-foreground` suffix, because two of them
 * are not suffix pairs: `muted-foreground` is used on the page background far
 * more often than on `muted` (every "3 students found" line), and a rule that
 * only checked it against `muted` would pass while the commonest secondary text
 * in the product failed.
 */
const TEXT_PAIRS: Array<[string, string]> = [
  ['foreground', 'background'],
  ['card-foreground', 'card'],
  ['popover-foreground', 'popover'],
  ['primary-foreground', 'primary'],
  ['secondary-foreground', 'secondary'],
  ['muted-foreground', 'muted'],
  ['muted-foreground', 'background'],
  ['muted-foreground', 'card'],
  ['accent-foreground', 'accent'],
  ['destructive-foreground', 'destructive'],
  ['success-foreground', 'success'],
  ['warning-foreground', 'warning'],
  ['primary', 'background'],
  ['destructive', 'background'],
]

/**
 * Non-text pairs, at the 3:1 that WCAG 1.4.11 asks of a control's boundary.
 *
 * `--input` is the outline of a text field and `--ring` is the focus indicator:
 * both are how somebody finds where to type. `--border` is deliberately absent
 * -- it draws decorative hairlines between cards, which 1.4.11 exempts, and
 * holding it to 3:1 would make every divider a heavy grey rule.
 */
const CONTROL_PAIRS: Array<[string, string]> = [
  ['input', 'background'],
  ['input', 'card'],
  ['ring', 'background'],
]

const CHARTS = ['chart-1', 'chart-2', 'chart-3', 'chart-4', 'chart-5']

describe('the design tokens', () => {
  it('defines every token Tailwind turns into a class, in both themes', () => {
    expect(NAMED_BY_TAILWIND.length).toBeGreaterThan(15)

    for (const [theme, tokens] of Object.entries(THEMES)) {
      const missing = NAMED_BY_TAILWIND.filter((token) => !(token in tokens))
      expect(missing, `${theme} defines no value for these`).toEqual([])
    }
  })

  it('defines the same set of tokens in both themes', () => {
    // A token present in one mode only reads as the other mode's value, which
    // is whatever :root left behind -- so dark mode silently renders a light
    // colour rather than failing.
    expect(Object.keys(DARK).sort()).toEqual(
      Object.keys(LIGHT).filter((t) => t !== 'radius').sort(),
    )
  })

  it.each(Object.keys(THEMES))('holds every token in %s to the H S%% L%% shape', (theme) => {
    for (const [name, value] of Object.entries(THEMES[theme as keyof typeof THEMES])) {
      if (name === 'radius') continue
      expect(() => parseHsl(value), `--${name}: ${value}`).not.toThrow()
    }
  })

  describe.each(Object.keys(THEMES))('%s theme', (theme) => {
    const tokens = THEMES[theme as keyof typeof THEMES]
    const ratio = (a: string, b: string) => contrastRatio(parseHsl(tokens[a]), parseHsl(tokens[b]))

    it.each(TEXT_PAIRS)('reads %s on %s at WCAG AA (4.5:1)', (fg, bg) => {
      expect(ratio(fg, bg)).toBeGreaterThanOrEqual(4.5)
    })

    it.each(CONTROL_PAIRS)('makes %s visible against %s (3:1)', (fg, bg) => {
      expect(ratio(fg, bg)).toBeGreaterThanOrEqual(3)
    })

    it('keeps every chart colour visible on the page', () => {
      for (const chart of CHARTS) {
        expect(ratio(chart, 'background'), chart).toBeGreaterThanOrEqual(3)
      }
    })

    it('keeps the chart colours apart from each other', () => {
      // Distinguishable, not merely visible: five tints of one hue all pass the
      // rule above and are useless side by side in a legend.
      const hues = CHARTS.map((chart) => parseHsl(tokens[chart]).h)
      for (let i = 0; i < hues.length; i++) {
        for (let j = i + 1; j < hues.length; j++) {
          expect(hueDistance(hues[i], hues[j]), `${CHARTS[i]} vs ${CHARTS[j]}`).toBeGreaterThan(25)
        }
      }
    })
  })
})
