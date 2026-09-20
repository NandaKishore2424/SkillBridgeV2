import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'

import { API } from '@/test/handlers'
import { renderWithProviders, signIn } from '@/test/render'
import { server } from '@/test/server'

import { StudentDetail } from './StudentDetail'

/**
 * The student detail page.
 *
 * The test that earns its place here is the third one. The endpoint is
 * `POST /api/v1/admin/students/{id}/resend-invitation`, sitting under
 * `/admin/students`, next to `GET /admin/students/{id}` and
 * `PUT /admin/students/{id}` -- all of which take a **student** id. That one
 * takes a **user** id: `InvitationService.resend(Long userId, ...)` looks the
 * account up by it.
 *
 * Nothing about the URL says so. Passing the student id would return 200 for
 * whichever account happened to have that user id, reissue a stranger's
 * password, and mail it to them -- and the admin would see "Invitation sent
 * again". The two numbers are deliberately different in this fixture so that
 * the wrong one cannot pass.
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
  enrolledBatchIds: [1, 2],
}

function stubStudent(overrides: Partial<typeof STUDENT> = {}) {
  server.use(
    http.get(`${API}/admin/students/7`, () => HttpResponse.json({ ...STUDENT, ...overrides })),
    http.get(`${API}/admin/students/7/skill-gap`, () => new HttpResponse(null, { status: 204 })),
  )
}

beforeEach(() => {
  signIn({ role: 'COLLEGE_ADMIN' })
})

describe('the student detail page', () => {
  it('shows what the college holds on the student', async () => {
    stubStudent()

    renderWithProviders(<StudentDetail />, { route: '/admin/students/7', path: '/admin/students/:id' })

    expect(await screen.findByRole('heading', { name: 'Asha Verma' })).toBeVisible()
    for (const value of ['asha@example.invalid', 'SBU007', 'B.Tech', 'CSE']) {
      expect(screen.getAllByText(value).length).toBeGreaterThan(0)
    }
    // Two enrolled batches, counted rather than listed.
    expect(screen.getByText('Enrolled batches').parentElement).toHaveTextContent('2')
  })

  it('offers a way back to the list it was reached from', async () => {
    stubStudent()

    renderWithProviders(<StudentDetail />, { route: '/admin/students/7', path: '/admin/students/:id' })

    expect(await screen.findByRole('link', { name: /all students/i })).toHaveAttribute(
      'href',
      '/admin/students',
    )
  })

  it('resends the invitation for the user id, not the student id', async () => {
    stubStudent()
    const asked: string[] = []
    server.use(
      http.post(`${API}/admin/students/:id/resend-invitation`, ({ params }) => {
        asked.push(String(params.id))
        return new HttpResponse(null, { status: 200 })
      }),
    )

    renderWithProviders(<StudentDetail />, { route: '/admin/students/7', path: '/admin/students/:id' })
    await screen.findByRole('heading', { name: 'Asha Verma' })
    await userEvent.click(screen.getByRole('button', { name: /resend invitation/i }))

    // 70, not 7. The student id is in the URL and is the wrong number to send.
    await waitFor(() => expect(asked).toEqual(['70']))
  })

  it('deactivates by student id, which is what that endpoint does take', async () => {
    stubStudent()
    const asked: Array<{ id: string; body: unknown }> = []
    server.use(
      http.patch(`${API}/admin/students/:id/status`, async ({ params, request }) => {
        asked.push({ id: String(params.id), body: await request.json() })
        return HttpResponse.json({ ...STUDENT, isActive: false })
      }),
    )

    renderWithProviders(<StudentDetail />, { route: '/admin/students/7', path: '/admin/students/:id' })
    await screen.findByRole('heading', { name: 'Asha Verma' })
    await userEvent.click(screen.getByRole('button', { name: /deactivate/i }))

    await waitFor(() => expect(asked).toEqual([{ id: '7', body: { isActive: false } }]))
  })

  it('offers "Activate" for an inactive student, not "Deactivate"', async () => {
    stubStudent({ isActive: false })

    renderWithProviders(<StudentDetail />, { route: '/admin/students/7', path: '/admin/students/:id' })

    expect(await screen.findByRole('button', { name: /^activate$/i })).toBeVisible()
    expect(screen.queryByRole('button', { name: /deactivate/i })).toBeNull()
  })

  it('says the student is missing rather than that the page broke', async () => {
    server.use(
      http.get(`${API}/admin/students/7`, () =>
        HttpResponse.json({ error: 'NOT_FOUND', message: 'No such student', status: 404 }, { status: 404 })),
    )

    renderWithProviders(<StudentDetail />, { route: '/admin/students/7', path: '/admin/students/:id' })

    // A 404 is an answer, not a failure: "could not load" would send the admin
    // to check their connection for a student who simply is not there.
    expect(await screen.findByText(/no such student/i)).toBeVisible()
    expect(screen.queryByText(/could not load/i)).toBeNull()
  })

  it('shows the server\'s own message when the request really does fail', async () => {
    server.use(
      http.get(`${API}/admin/students/7`, () =>
        HttpResponse.json(
          { error: 'INTERNAL_ERROR', message: 'Reference 9f2c1a.', status: 500 },
          { status: 500 },
        )),
    )

    renderWithProviders(<StudentDetail />, { route: '/admin/students/7', path: '/admin/students/:id' })

    // The correlation id is the only thing that makes a 500 actionable.
    expect(await screen.findByText(/Reference 9f2c1a\./)).toBeVisible()
  })
})
