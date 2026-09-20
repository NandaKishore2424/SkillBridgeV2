import { screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'

import App from './App'
import { API } from './test/handlers'
import { renderWithProviders, signIn } from './test/render'
import { server } from './test/server'

/**
 * The route table, exercised through the real `App`.
 *
 * `shared/rules/routes.test.ts` proves every link points at a declared route.
 * That is a static check and it cannot see two things:
 *
 * 1. **Which route wins.** `/admin/students/upload` and `/admin/students/:id`
 *    both match `/admin/students/upload`. React Router ranks by specificity
 *    rather than by order, so the literal should win -- but "should" is the
 *    word that precedes an outage. If it ever loses, the bulk-upload screen
 *    becomes a student detail page for a student called "upload".
 * 2. **What an unknown URL does.** This used to be a silent redirect to the
 *    landing page, which is what hid the dead links in the first place.
 */

const STUDENT = {
  id: 7,
  userId: 70,
  email: 'asha@example.invalid',
  fullName: 'Asha Verma',
  rollNumber: 'SBU007',
  degree: 'B.Tech',
  branch: 'CSE',
  year: 3,
  isActive: true,
  enrolledBatchIds: [1],
}

beforeEach(() => {
  server.use(
    http.get(`${API}/admin/students/7`, () => HttpResponse.json(STUDENT)),
    http.get(`${API}/admin/students/7/skill-gap`, () => new HttpResponse(null, { status: 204 })),
    http.get(`${API}/admin/students/bulk-upload/history`, () =>
      HttpResponse.json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })),
  )
})

describe('the route table', () => {
  it('shows a 404 page for an unknown URL, not the landing page', async () => {
    renderWithProviders(<App />, { route: '/admin/nothing-here' })

    expect(await screen.findByRole('heading', { name: /this page does not exist/i })).toBeVisible()
    // The address is shown back: "that does not exist" is only useful if you
    // can see which one was tried.
    expect(screen.getByText('/admin/nothing-here')).toBeInTheDocument()
    // The failure this replaces: silently rendering the marketing page.
    expect(screen.queryByRole('heading', { name: /training that knows/i })).toBeNull()
  })

  it('prefers the literal route over the parameter that also matches it', async () => {
    signIn({ role: 'COLLEGE_ADMIN' })

    renderWithProviders(<App />, { route: '/admin/students/upload' })

    // If `:id` won, this would be a detail page fetching student "upload".
    await waitFor(() =>
      expect(screen.queryByRole('heading', { name: 'Asha Verma' })).toBeNull(),
    )
    expect(await screen.findByRole('heading', { name: /upload/i })).toBeVisible()
  })

  it('opens the student detail page that "View Details" points at', async () => {
    signIn({ role: 'COLLEGE_ADMIN' })

    renderWithProviders(<App />, { route: '/admin/students/7' })

    expect(await screen.findByRole('heading', { name: 'Asha Verma' })).toBeVisible()
    // The route existed nowhere before this; every click went to the landing page.
    expect(screen.queryByRole('heading', { name: /this page does not exist/i })).toBeNull()
  })

  it('answers a non-numeric id without asking the server about it', async () => {
    signIn({ role: 'COLLEGE_ADMIN' })
    let asked = false
    server.use(
      http.get(`${API}/admin/students/:id`, () => {
        asked = true
        return new HttpResponse(null, { status: 400 })
      }),
    )

    renderWithProviders(<App />, { route: '/admin/students/not-a-number' })

    expect(await screen.findByText(/is not a student id/i)).toBeVisible()
    expect(asked, 'a typo in the URL should not become a request').toBe(false)
  })
})
