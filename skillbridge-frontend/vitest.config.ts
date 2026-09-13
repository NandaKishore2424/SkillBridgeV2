import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config'

/**
 * Vitest, layered on the real Vite config.
 *
 * `mergeConfig` rather than a fresh `defineConfig`: the tests must resolve `@/`
 * through the same alias the application does. Re-declaring it here would let
 * the two drift, and the failure mode is a test importing a different module
 * from the one that ships.
 *
 * `globals` is deliberately off. Importing `describe`/`it`/`expect` costs one
 * line per file and avoids teaching tsconfig about ambient test types, which
 * keeps `npm run build` type-checking tests exactly as strictly as it checks
 * the application.
 */
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
      restoreMocks: true,
      coverage: {
        provider: 'v8',
        reportsDirectory: './coverage',
        include: ['src/**/*.{ts,tsx}'],
        exclude: [
          // shadcn/radix primitives: vendored, unmodified, and testing them
          // tests the library rather than this application.
          'src/shared/components/ui/**',
          'src/test/**',
          'src/**/*.test.{ts,tsx}',
          'src/main.tsx',
          'src/vite-env.d.ts',
        ],
        /**
         * A RATCHET, not a target.
         *
         * Phase 11 asks for 70% lines. The suite is at 13.4% and these numbers
         * are set just under what is measured today, so coverage cannot fall
         * without failing the build, and every batch of tests raises the floor.
         * Setting 70% now would mean a red build with no way to go green except
         * deleting the threshold, and a threshold everyone deletes is worse than
         * none.
         *
         * The honest reading: 22 tests cover the logic that has actually broken
         * here -- the single-flight refresh, the idempotency key, the debounce,
         * and that the admin search reaches the server. Most of the codebase is
         * screens nobody has written a test for yet. **Do not read 13% as "the
         * frontend is 13% tested" and do not read a future 70% as "done":**
         * these files are mostly JSX, and a test that renders a page without
         * asserting anything specific moves this number a long way while proving
         * almost nothing.
         */
        thresholds: {
          lines: 13,
          statements: 13,
          functions: 8,
          branches: 6,
        },
      },
    },
  }),
)
