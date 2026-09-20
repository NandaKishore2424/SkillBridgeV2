import postcss from 'postcss'
import tailwindcss from 'tailwindcss'
import { describe, expect, it } from 'vitest'

import tailwindConfig from '../../../tailwind.config.js'

/**
 * `bg-primary/10` must actually be ten per cent.
 *
 * Tailwind resolves an opacity modifier by rewriting the colour function, and
 * whether it can do that depends on the shape of the value in the config. Get
 * it wrong and the class still exists and the element is still coloured -- at
 * full strength. Every tinted surface in the application (the hero wash, the
 * icon chips, the selected row) is a `/10` or `/15`, so the failure is
 * everywhere and looks like a design choice rather than a bug.
 *
 * This compiles the real config against a stub stylesheet, so it is Tailwind's
 * own answer rather than a reading of its documentation.
 */

async function compile(classes: string[]): Promise<string> {
  const result = await postcss([
    tailwindcss({
      ...tailwindConfig,
      // Only the classes under test, so the assertion below is not matching
      // some unrelated rule that happens to be in a 45 KB stylesheet.
      content: [{ raw: classes.join(' '), extension: 'html' }],
    }),
  ]).process('@tailwind utilities;', { from: undefined })
  return result.css
}

describe('opacity modifiers on the design tokens', () => {
  it('turns bg-primary/10 into a real alpha channel', async () => {
    const css = await compile(['bg-primary/10'])

    // `hsl(var(--primary) / .1)` -- the token, then the alpha.
    expect(css).toMatch(/background-color:\s*hsl\(var\(--primary\)\s*\/\s*0?\.1\)/)
  })

  it('does the same for text, borders and rings', async () => {
    const css = await compile(['text-muted-foreground/70', 'border-primary/20', 'ring-ring/50'])

    expect(css).toMatch(/color:\s*hsl\(var\(--muted-foreground\)\s*\/\s*0?\.7\)/)
    expect(css).toMatch(/border-color:\s*hsl\(var\(--primary\)\s*\/\s*0?\.2\)/)
    expect(css).toMatch(/--tw-ring-color:\s*hsl\(var\(--ring\)\s*\/\s*0?\.5\)/)
  })

  it('still emits the opaque colour with no modifier', async () => {
    const css = await compile(['bg-primary'])

    expect(css).toContain('--primary')
    expect(css).not.toMatch(/hsl\(var\(--primary\)\s*\/\s*0?\.\d/)
  })
})
