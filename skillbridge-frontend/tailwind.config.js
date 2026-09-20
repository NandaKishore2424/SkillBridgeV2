/** @type {import('tailwindcss').Config} */

/**
 * Every colour is `hsl(var(--token) / <alpha-value>)`.
 *
 * Measured, not assumed: on Tailwind 3.4 this compiles identically to the bare
 * `hsl(var(--token))` shadcn ships -- both emit
 * `background-color:hsl(var(--primary) / .1)` for `bg-primary/10`, because
 * Tailwind parses the colour function and injects the alpha itself. The
 * explicit placeholder is the documented contract rather than behaviour
 * inferred from the compiler, and `src/shared/design/tokens.test.ts` compiles
 * the real stylesheet and asserts the alpha survives, so a future Tailwind that
 * stops inferring it fails the build instead of rendering every tint at full
 * strength.
 */
const withAlpha = (token) => `hsl(var(--${token}) / <alpha-value>)`

const pair = (token) => ({
  DEFAULT: withAlpha(token),
  foreground: withAlpha(`${token}-foreground`),
})

export default {
  darkMode: ["class"],
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    container: {
      center: true,
      padding: { DEFAULT: "1rem", sm: "1.5rem", lg: "2rem" },
      screens: { "2xl": "1280px" },
    },
    extend: {
      borderRadius: {
        lg: "var(--radius)",
        md: "calc(var(--radius) - 2px)",
        sm: "calc(var(--radius) - 4px)",
      },
      colors: {
        background: withAlpha("background"),
        foreground: withAlpha("foreground"),
        card: pair("card"),
        popover: pair("popover"),
        primary: pair("primary"),
        secondary: pair("secondary"),
        muted: pair("muted"),
        accent: pair("accent"),
        destructive: pair("destructive"),
        success: pair("success"),
        warning: pair("warning"),
        border: withAlpha("border"),
        input: withAlpha("input"),
        ring: withAlpha("ring"),
        chart: {
          "1": withAlpha("chart-1"),
          "2": withAlpha("chart-2"),
          "3": withAlpha("chart-3"),
          "4": withAlpha("chart-4"),
          "5": withAlpha("chart-5"),
        },
      },
      keyframes: {
        "fade-up": {
          from: { opacity: "0", transform: "translateY(0.75rem)" },
          to: { opacity: "1", transform: "none" },
        },
      },
      animation: {
        "fade-up": "fade-up 0.5s cubic-bezier(0.16, 1, 0.3, 1) both",
      },
    },
  },
  plugins: [require("tailwindcss-animate")],
}
