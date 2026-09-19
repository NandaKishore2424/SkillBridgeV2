import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'

import { API } from '@/test/handlers'
import { renderWithProviders, signIn } from '@/test/render'
import { server } from '@/test/server'

import { UploadDetails } from './BulkUploadScreen'

/**
 * What an admin sees after an import, modelled on the backend's answers
 * (CsvImportTest): the rows that need attention, each with the reason the
 * server wrote, and "Resend invitation" only where an account exists but its
 * email did not go out.
 *
 * Before 2026-09-19 the screen showed a failure count and nothing else; the
 * rows and their reasons were in the database and nowhere on screen.
 */

let rowRequests: URL[]
let resendCalls: number

beforeEach(() => {
  signIn({ role: 'COLLEGE_ADMIN' })
  rowRequests = []
  resendCalls = 0
  server.use(
    http.get(`${API}/admin/bulk-uploads/7`, () =>
      HttpResponse.json({
        id: 7, entityType: 'STUDENT', fileName: 'students.csv', status: 'COMPLETED',
        totalRows: 3, successfulRows: 2, failedRows: 1, emailFailedRows: 1,
        errorReport: null, createdAt: '2026-09-19T10:00:00', completedAt: '2026-09-19T10:00:05',
      })),
    http.get(`${API}/admin/bulk-uploads/7/rows`, ({ request }) => {
      rowRequests.push(new URL(request.url))
      return HttpResponse.json({
        items: [
          { rowNumber: 3, status: 'FAILED', message: 'Email is not a valid address',
            values: { 'Full Name': 'No Email', Email: 'not-an-email' }, userId: null },
          { rowNumber: 4, status: 'EMAIL_FAILED', message: 'Account created, but the invitation email was not sent.',
            values: { 'Full Name': 'Asha Verma', Email: 'asha@college.edu' }, userId: 55 },
        ],
        page: 0, size: 200, totalElements: 2, totalPages: 1, first: true, last: true, sort: '',
      })
    }),
    http.post(`${API}/admin/students/55/resend-invitation`, () => {
      resendCalls += 1
      // The provider is still down the first time, back the second.
      return resendCalls === 1
        ? HttpResponse.json({ status: 502, error: 'EMAIL_NOT_SENT', message: 'Something went wrong' }, { status: 502 })
        : new HttpResponse(null, { status: 200 })
    }),
  )
})

describe('UploadDetails', () => {
  it('lists the rows needing attention with the reasons the server gave', async () => {
    renderWithProviders(<UploadDetails kind="students" uploadId={7} />)

    expect(await screen.findByText('Email is not a valid address')).toBeInTheDocument()
    expect(screen.getByText('No Email')).toBeInTheDocument()
    expect(screen.getByText(/1 failed, 1 not emailed/)).toBeInTheDocument()
    expect(rowRequests[0].searchParams.get('status')).toBe('FAILED,EMAIL_FAILED')
  })

  it('offers a resend only where an account exists, and keeps offering it until one succeeds', async () => {
    renderWithProviders(<UploadDetails kind="students" uploadId={7} />)

    const buttons = await screen.findAllByRole('button', { name: /resend invitation/i })
    expect(buttons).toHaveLength(1)

    await userEvent.click(buttons[0])
    await waitFor(() => expect(resendCalls).toBe(1))
    expect(await screen.findByRole('button', { name: /resend invitation/i })).toBeEnabled()
    expect(screen.queryByText('Sent')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /resend invitation/i }))
    expect(await screen.findByText('Sent')).toBeInTheDocument()
    expect(resendCalls).toBe(2)
  })
})
