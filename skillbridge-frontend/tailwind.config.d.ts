/**
 * Types for the Tailwind config, so a test can import it.
 *
 * `tailwindAlpha.test.ts` compiles the real config rather than a copy of it --
 * a copy would pass while the shipped stylesheet was broken. TypeScript will
 * not import a `.js` file without this, and `allowJs` would pull every config
 * file in the project into the build.
 */
import type { Config } from 'tailwindcss'

declare const config: Config
export default config
