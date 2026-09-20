/**
 * Colour arithmetic for the design-token rules.
 *
 * Lives in `src/` rather than in the test file because the test is not its only
 * caller: anything that has to decide whether text will read on a colour --
 * today the token rules, tomorrow a generated chart legend -- needs the same
 * numbers, and two implementations of a contrast formula would disagree.
 *
 * The formulae are WCAG 2.1: relative luminance from sRGB with the 0.03928
 * linearisation, and contrast as `(L1 + 0.05) / (L2 + 0.05)`.
 */

export interface Hsl {
  /** Degrees, 0-360. */
  h: number
  /** Per cent, 0-100. */
  s: number
  /** Per cent, 0-100. */
  l: number
}

/**
 * Parses the `H S% L%` triple a design token holds.
 *
 * The tokens are stored unwrapped -- `243 72% 51%`, not `hsl(243 72% 51%)` --
 * so that Tailwind can wrap them and add an alpha channel. That is also why
 * this cannot simply hand the string to the browser.
 */
export function parseHsl(token: string): Hsl {
  const match = token
    .trim()
    .match(/^(-?[\d.]+)(?:deg)?\s+(-?[\d.]+)%\s+(-?[\d.]+)%$/)
  if (!match) {
    throw new Error(`not an "H S% L%" triple: ${JSON.stringify(token)}`)
  }
  return { h: Number(match[1]), s: Number(match[2]), l: Number(match[3]) }
}

/** sRGB channels, each 0-1. */
export function hslToRgb({ h, s, l }: Hsl): [number, number, number] {
  const sat = s / 100
  const light = l / 100
  const c = (1 - Math.abs(2 * light - 1)) * sat
  const hp = (((h % 360) + 360) % 360) / 60
  const x = c * (1 - Math.abs((hp % 2) - 1))
  const [r, g, b] =
    hp < 1 ? [c, x, 0]
    : hp < 2 ? [x, c, 0]
    : hp < 3 ? [0, c, x]
    : hp < 4 ? [0, x, c]
    : hp < 5 ? [x, 0, c]
    : [c, 0, x]
  const m = light - c / 2
  return [r + m, g + m, b + m]
}

/** WCAG 2.1 relative luminance, 0 (black) to 1 (white). */
export function relativeLuminance(colour: Hsl): number {
  const [r, g, b] = hslToRgb(colour).map((channel) =>
    channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4,
  )
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/**
 * Contrast ratio between two opaque colours, 1 (identical) to 21 (black on
 * white). Order does not matter.
 */
export function contrastRatio(a: Hsl, b: Hsl): number {
  const [lighter, darker] = [relativeLuminance(a), relativeLuminance(b)].sort((x, y) => y - x)
  return (lighter + 0.05) / (darker + 0.05)
}

/** Smallest angle between two hues, 0-180 degrees. */
export function hueDistance(a: number, b: number): number {
  const diff = Math.abs(((a - b) % 360) + 360) % 360
  return diff > 180 ? 360 - diff : diff
}
