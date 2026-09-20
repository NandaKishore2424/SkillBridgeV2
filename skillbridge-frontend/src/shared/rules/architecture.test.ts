import { describe, expect, it } from 'vitest'

import { sourceFiles } from './sourceFiles'

/**
 * Rules that fail the build rather than the browser.
 *
 * Each one exists because the mistake it catches is invisible in review and
 * only shows up somewhere far away -- a blank page in production, a leaked
 * token, a test that silently stops testing.
 */

const NODE_BUILTIN = /from ['"](node:[a-z/]+|fs|path|os|crypto|child_process)['"]/

describe('what application code may import', () => {
  it('keeps Node built-ins out of anything the browser loads', () => {
    // Three test files read index.css, index.html and tailwind.config.js off
    // disk, which is what makes them worth having. The same import in a
    // component type-checks, bundles, and then throws at runtime in the
    // browser -- Vite externalises `node:fs` and the page renders blank.
    const offenders = sourceFiles({ tests: false })
      .filter((file) => NODE_BUILTIN.test(file.text))
      .map((file) => file.path)

    expect(offenders).toEqual([])
  })

  it('has rule helpers that only the rules use', () => {
    // sourceFiles.ts itself reads the filesystem, so it must never be reachable
    // from a component. Nothing but a *.test.ts may import it.
    const importers = sourceFiles()
      .filter((file) => /from '[^']*sourceFiles'/.test(file.text))
      .map((file) => file.path)

    expect(importers.length).toBeGreaterThan(0)
    expect(importers.filter((path) => !/\.test\.tsx?$/.test(path))).toEqual([])
  })
})
