import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import { sourceFiles, SRC } from './sourceFiles'

/**
 * Every link in the application has to go somewhere.
 *
 * `App.tsx` used to end with `<Route path="*" element={<Navigate to="/" replace />} />`.
 * That line is why this rule exists: a `<Link>` to a path with no route did not
 * 404, warn or log. It quietly returned the person to the landing page, which
 * they read as "it logged me out". Nothing in a build, a type check or a render
 * test saw it.
 *
 * Four such links were in the application when this was written -- View Details
 * on the students, trainers and companies lists, and both buttons on every
 * batch card on the student dashboard. One had been recorded in the handover as
 * a known defect since Phase 2; the other three nobody had noticed.
 *
 * The check is deliberately dumb: read the route table out of the real
 * `App.tsx`, read every internal target out of the real source, and match them.
 */

const APP = readFileSync(resolve(SRC, 'App.tsx'), 'utf8')

export interface RouteMatcher {
  pattern: string
  matches: (path: string) => boolean
}

function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** `path="/admin/students/:id"` -> a matcher for concrete paths. */
function toMatcher(pattern: string): RouteMatcher {
  if (pattern === '*') {
    // The catch-all matches every path there is, which is exactly what made a
    // dead link invisible. It is not a destination, so it must not satisfy one.
    return { pattern, matches: () => false }
  }
  const regex = new RegExp(
    `^${pattern
      .split('/')
      .map((segment) => (segment.startsWith(':') ? '[^/]+' : escape(segment)))
      .join('/')}$`,
  )
  return { pattern, matches: (path: string) => regex.test(path) }
}

function routeMatchers(): RouteMatcher[] {
  const paths = [...APP.matchAll(/<Route\s+path="([^"]+)"/g)].map(([, path]) => path)
  if (paths.length < 10) {
    throw new Error(`only found ${paths.length} routes in App.tsx; the parser is out of date`)
  }
  return paths.map(toMatcher)
}

interface LinkTarget {
  file: string
  /** As written, `${...}` and all. */
  raw: string
  /** Normalised to something a route table can be asked about. */
  target: string
}

/**
 * Every internal destination the source can produce.
 *
 * `to={`/admin/students/${student.id}`}` becomes `/admin/students/1`: the
 * expression is one path segment, and what it evaluates to does not matter to a
 * route table. Query and hash are dropped, because `/student/progress` and
 * `/student/progress?batchId=3` are the same route.
 */
function linkTargets(): LinkTarget[] {
  const found: LinkTarget[] = []

  for (const file of sourceFiles({ tests: false })) {
    if (file.path === 'App.tsx') continue
    const patterns = [/\bto=\{?[`'"](\/[^`'"]*)[`'"]/g, /\bnavigate\(\s*[`'"](\/[^`'"]*)[`'"]/g]
    for (const pattern of patterns) {
      for (const [, raw] of file.text.matchAll(pattern)) {
        found.push({
          file: file.path,
          raw,
          target: raw.replace(/\$\{[^}]*\}/g, '1').split(/[?#]/)[0],
        })
      }
    }
  }
  return found
}

/** The rule itself, so it can be run against made-up input as well as the real thing. */
function deadLinks(targets: LinkTarget[], routes: RouteMatcher[]): string[] {
  const dead = targets
    .filter(({ target }) => !routes.some((route) => route.matches(target)))
    .map(({ file, raw }) => `${file} -> ${raw}`)
  return [...new Set(dead)].sort()
}

describe('the rule itself', () => {
  // Checked first, because a rule that cannot fail is worse than no rule: the
  // "no dead links" result below means nothing unless this passes.
  const routes = [toMatcher('/admin/students'), toMatcher('/admin/students/:id'), toMatcher('*')]

  it('reports a link to a path with no route', () => {
    const dead = deadLinks(
      [{ file: 'x.tsx', raw: '/admin/reports', target: '/admin/reports' }],
      routes,
    )

    expect(dead).toEqual(['x.tsx -> /admin/reports'])
  })

  it('does not let the catch-all satisfy a link', () => {
    // With `*` treated as a wildcard, every path in existence "has a route" and
    // the rule below passes for ever while the application is full of dead
    // links. That is the pre-existing bug, expressed as a test.
    expect(routes.some((route) => route.pattern === '*')).toBe(true)
    expect(toMatcher('*').matches('/anything/at/all')).toBe(false)
    expect(deadLinks([{ file: 'x.tsx', raw: '/nope', target: '/nope' }], [toMatcher('*')])).toEqual([
      'x.tsx -> /nope',
    ])
  })

  it('accepts a parameter segment, and only one segment', () => {
    expect(deadLinks([{ file: 'x.tsx', raw: '/admin/students/${id}', target: '/admin/students/1' }], routes))
      .toEqual([])
    // `:id` is one segment: a link two levels deeper has no route.
    expect(deadLinks([{ file: 'x.tsx', raw: '/admin/students/1/edit', target: '/admin/students/1/edit' }], routes))
      .toEqual(['x.tsx -> /admin/students/1/edit'])
  })

  it('normalises a template expression and drops the query', () => {
    const targets = linkTargets()
    expect(targets.every(({ target }) => !target.includes('${'))).toBe(true)
    expect(targets.every(({ target }) => !target.includes('?'))).toBe(true)
  })
})

describe('internal links', () => {
  it('finds enough of them to be worth checking', () => {
    // A regex that quietly stopped matching would make the rule pass vacuously.
    expect(linkTargets().length).toBeGreaterThan(20)
  })

  it('all resolve to a declared route', () => {
    expect(deadLinks(linkTargets(), routeMatchers())).toEqual([])
  })
})
