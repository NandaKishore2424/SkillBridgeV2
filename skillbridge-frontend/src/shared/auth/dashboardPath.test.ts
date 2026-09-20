import { describe, expect, it } from 'vitest'

import { DASHBOARD_PATH, dashboardPathFor } from './dashboardPath'
import { sourceFiles } from '@/shared/rules/sourceFiles'

/**
 * Sign-in has to land somewhere, and that somewhere used to be written out
 * four times.
 *
 * The failure this stops is dull and expensive: a role's dashboard moves, three
 * of the four copies are updated, and the fourth sends that role to a path with
 * no route -- which the catch-all turns into a silent redirect to the public
 * page. The person sees the marketing site after a successful login and reports
 * "it logged me out".
 */
describe('where each role lands', () => {
  it.each(Object.entries(DASHBOARD_PATH))('sends %s to %s', (_role, path) => {
    expect(path.startsWith('/')).toBe(true)
  })

  it('gives every role a distinct dashboard', () => {
    const paths = Object.values(DASHBOARD_PATH)
    expect(new Set(paths).size).toBe(paths.length)
  })

  it('falls back to the public page for a role it does not know', () => {
    // Roles arrive in a token claim. An unrecognised one is data from outside.
    expect(dashboardPathFor('SUPER_USER')).toBe('/')
    expect(dashboardPathFor(undefined)).toBe('/')
    expect(dashboardPathFor(null)).toBe('/')
    expect(dashboardPathFor('')).toBe('/')
  })

  it('is the only place that spells a dashboard path out', () => {
    // The rule, not the tidy-up: a second copy is what lets the two drift.
    const offenders = sourceFiles()
      .filter((file) => file.path !== 'shared/auth/dashboardPath.ts')
      .filter((file) => file.path !== 'App.tsx') // declares the routes themselves
      .filter((file) => !file.path.endsWith('.test.ts') && !file.path.endsWith('.test.tsx'))
      .filter((file) => file.path !== 'shared/components/layout/Sidebar.tsx') // nav menu, not a landing
      .filter((file) => Object.values(DASHBOARD_PATH).some((path) => file.text.includes(`'${path}'`)))
      .map((file) => file.path)

    expect(offenders).toEqual([])
  })
})
