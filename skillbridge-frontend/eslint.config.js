import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  // `coverage/` is generated: istanbul ships prettify.js and sorter.js inside
  // it, and linting those reports problems no edit to this repository can fix.
  globalIgnores(['dist', 'coverage']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
  },
  {
    /*
      Last, so it overrides the block above: in flat config the later object
      wins, and putting this first meant the general `extends` simply turned the
      rule back on.

      shadcn primitives are vendored verbatim from ui.shadcn.com, and three of
      them export a `cva` variants object beside the component. Fast refresh
      cannot follow that, which is a real cost -- editing `button.tsx` reloads
      the page instead of hot-swapping it -- but it is a cost of vendoring, and
      splitting the files means every future `npx shadcn add` conflicts. Off for
      these files only.
    */
    files: ['src/shared/components/ui/**/*.tsx'],
    rules: { 'react-refresh/only-export-components': 'off' },
  },
])
