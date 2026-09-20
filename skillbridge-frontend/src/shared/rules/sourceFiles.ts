import { readFileSync, readdirSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'

/**
 * Walks `src/` for the rule tests.
 *
 * Shared because the rules keep needing the same three things -- every file,
 * its path relative to `src`, and its text -- and three copies of a directory
 * walk drift in what they skip.
 */

export const SRC = resolve(__dirname, '../..')

export interface SourceFile {
  /** Relative to `src/`, with forward slashes: `pages/Landing.tsx`. */
  path: string
  text: string
}

const CODE = /\.(ts|tsx)$/

export function sourceFiles(
  { tests = true }: { tests?: boolean } = {},
): SourceFile[] {
  const found: SourceFile[] = []

  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name)
      if (entry.isDirectory()) {
        walk(full)
      } else if (CODE.test(entry.name)) {
        found.push({ path: relative(SRC, full).split('\\').join('/'), text: readFileSync(full, 'utf8') })
      }
    }
  }

  walk(SRC)
  return tests ? found : found.filter((file) => !isTest(file.path))
}

/**
 * Everything that exists only so that tests can run.
 *
 * `shared/rules/` is in here with the test files themselves: its helpers read
 * the filesystem and are imported by nothing else, which the rule below checks
 * rather than assumes.
 */
export function isTest(path: string): boolean {
  return /\.test\.tsx?$/.test(path) || path.startsWith('test/') || path.startsWith('shared/rules/')
}
