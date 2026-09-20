import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'

import { API } from '@/test/handlers'
import { renderWithProviders, signIn } from '@/test/render'
import { server } from '@/test/server'

import { Dashboard } from './Dashboard'
import { SystemAdminDashboard } from './SystemAdminDashboard'

/**
 * The two admin dashboards.
 *
 * These tests exist because of what was on these pages rather than how they
 * looked. Three things were on screen that nothing produced:
 *
 * - **"Total Students: 0"** on the system dashboard, described as "Across all
 *   colleges". Not a count that happened to be zero -- the string `"0"`, typed
 *   into the JSX. `GET /admin/colleges` has never carried a student count.
 * - **"System Health: 100% -- All systems operational"**, measuring nothing. It
 *   would have read 100% with the database down.
 * - **"Analytics -- coming soon"**, with a permanently disabled button, on the
 *   first screen a college admin sees.
 *
 * A number nobody can trace is worse than a gap, and none of the three could
 * fail: that is exactly why they survived. The tests below assert the figures
 * move with the response, and that the invented ones are gone.
 */

const STATS = {
  totalBatches: 12,
  activeBatches: 5,
  totalStudents: 348,
  totalTrainers: 9,
  totalCompanies: 21,
}

const COLLEGES = {
  items: [
    { id: 1, name: 'Saveetha Engineering College', code: 'SEC', status: 'ACTIVE' },
    { id: 2, name: 'Coastal Institute', code: 'CI', status: 'ACTIVE' },
    { id: 3, name: 'Old Polytechnic', code: 'OP', status: 'INACTIVE' },
  ],
  page: 0,
  size: 20,
  totalElements: 3,
  totalPages: 1,
}

describe('the college admin dashboard', () => {
  beforeEach(() => {
    signIn({ role: 'COLLEGE_ADMIN' })
    server.use(http.get(`${API}/admin/dashboard/stats`, () => HttpResponse.json(STATS)))
  })

  it('prints the figures the server sent', async () => {
    renderWithProviders(<Dashboard />)

    const summary = await screen.findByRole('region', { name: 'At a glance' })
    // Awaited inside the region: `findByRole` resolves the moment the section
    // exists, which is while it still holds skeletons.
    expect(await within(summary).findByText('12')).toBeVisible()
    for (const value of ['348', '9', '21']) {
      expect(within(summary).getByText(value)).toBeVisible()
    }
    expect(within(summary).getByText(/5 running now/i)).toBeVisible()
  })

  it('moves when the figures move', async () => {
    // The point of the previous test is only made by this one: a hardcoded
    // "348" would pass it.
    server.use(
      http.get(`${API}/admin/dashboard/stats`, () =>
        HttpResponse.json({ ...STATS, totalStudents: 7 })),
    )

    renderWithProviders(<Dashboard />)

    const summary = await screen.findByRole('region', { name: 'At a glance' })
    expect(await within(summary).findByText('7')).toBeVisible()
    expect(within(summary).queryByText('348')).toBeNull()
  })

  it('makes each figure a link to the thing it counts', async () => {
    renderWithProviders(<Dashboard />)

    const summary = await screen.findByRole('region', { name: 'At a glance' })
    await within(summary).findByText('12')
    const hrefs = within(summary).getAllByRole('link').map((link) => link.getAttribute('href'))
    for (const path of ['/admin/batches', '/admin/students', '/admin/trainers', '/admin/companies']) {
      expect(hrefs).toContain(path)
    }
  })

  it('promises nothing it cannot do', async () => {
    renderWithProviders(<Dashboard />)

    await screen.findByText('12')
    expect(screen.queryByText(/coming soon/i)).toBeNull()
    // A permanently disabled button is the same promise with a border on it.
    for (const button of screen.getAllByRole('button')) {
      expect(button).toBeEnabled()
    }
  })

  it('says what went wrong rather than "please try again"', async () => {
    server.use(
      http.get(`${API}/admin/dashboard/stats`, () =>
        HttpResponse.json(
          { error: 'FORBIDDEN', message: 'Your account is not attached to a college.', status: 403 },
          { status: 403 },
        )),
    )

    renderWithProviders(<Dashboard />)

    // 403 here means the account has no college -- a real, fixable condition
    // that "Failed to load dashboard statistics" hid completely.
    expect(await screen.findByText(/not attached to a college/i)).toBeVisible()
  })
})

describe('the system admin dashboard', () => {
  beforeEach(() => {
    signIn({ role: 'SYSTEM_ADMIN', collegeId: null })
    server.use(http.get(`${API}/admin/colleges`, () => HttpResponse.json(COLLEGES)))
  })

  it('counts the colleges it was given', async () => {
    renderWithProviders(<SystemAdminDashboard />)

    const summary = await screen.findByRole('region', { name: 'At a glance' })
    expect((await within(summary).findByText('Colleges')).parentElement).toHaveTextContent('3')
    expect(within(summary).getByText('Active').parentElement).toHaveTextContent('2')
    expect(within(summary).getByText('Inactive').parentElement).toHaveTextContent('1')
  })

  it('shows no student count, because no endpoint gives one', async () => {
    renderWithProviders(<SystemAdminDashboard />)

    await within(await screen.findByRole('region', { name: 'At a glance' })).findByText('Colleges')
    // The card that used to be here printed a hardcoded "0" under the label
    // "Total Students / Across all colleges".
    expect(screen.queryByText(/total students/i)).toBeNull()
    expect(screen.queryByText(/across all colleges/i)).toBeNull()
  })

  it('shows no health indicator, because nothing measures health', async () => {
    renderWithProviders(<SystemAdminDashboard />)

    await within(await screen.findByRole('region', { name: 'At a glance' })).findByText('Colleges')
    expect(screen.queryByText(/system health/i)).toBeNull()
    expect(screen.queryByText(/all systems operational/i)).toBeNull()
    expect(screen.queryByText('100%')).toBeNull()
  })
})
