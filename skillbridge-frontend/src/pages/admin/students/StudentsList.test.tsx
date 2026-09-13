import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'

import { API } from '@/test/handlers'
import { renderWithProviders, signIn } from '@/test/render'
import { server } from '@/test/server'

import { StudentsList } from './StudentsList'

/**
 * The search box must reach the server.
 *
 * Every admin search box on this project once filtered the array already on
 * screen. A student matching on page 3 was invisible from page 1, and the
 * screen looked completely correct while doing it: you type, rows disappear,
 * the ones left match. The bug is only visible if you know how many rows there
 * should have been.
 *
 * That makes it almost untestable by assertion on the rendered list -- a
 * client-side filter and a server-side one produce the same DOM for the common
 * case. So these tests assert the two things that actually differ:
 *
 *   1. the request carries `search`, and
 *   2. a row the server returns is rendered **even though it does not match
 *      what was typed**.
 *
 * The second is the load-bearing one. No client-side filter can pass it, and it
 * needs no knowledge of how the component is written.
 */

/** Every `/admin/students` request this test saw, newest last. */
let requests: URL[]

function studentRow(id: number, fullName: string, rollNumber: string) {
  return {
    id,
    userId: id + 100,
    fullName,
    rollNumber,
    email: `${rollNumber.toLowerCase()}@example.invalid`,
    degree: 'B.Tech',
    branch: 'CSE',
    year: 3,
    isActive: true,
    collegeId: 1,
  }
}

function pageOf(items: ReturnType<typeof studentRow>[], totalElements = items.length) {
  return {
    items,
    page: 0,
    size: 20,
    totalElements,
    totalPages: Math.max(1, Math.ceil(totalElements / 20)),
    first: true,
    last: true,
    sort: '',
  }
}

beforeEach(() => {
  requests = []
  signIn({ role: 'COLLEGE_ADMIN' })
})

/** Arms `/admin/students` to record its query and answer with `rows`. */
function serveStudents(rows: ReturnType<typeof studentRow>[]) {
  server.use(
    http.get(`${API}/admin/students`, ({ request }) => {
      const url = new URL(request.url)
      requests.push(url)
      return HttpResponse.json(pageOf(rows))
    }),
  )
}

describe('StudentsList', () => {
  it('renders the rows the server returned', async () => {
    serveStudents([
      studentRow(1, 'Arjun Kumar', 'SBU001'),
      studentRow(2, 'Priya Nair', 'SBU002'),
    ])

    renderWithProviders(<StudentsList />)

    // The table identifies a student by roll number and email; it has no name
    // column at all, which is worth knowing before writing an assertion about
    // this screen.
    expect(await screen.findByText('SBU001')).toBeInTheDocument()
    expect(screen.getByText('SBU002')).toBeInTheDocument()
  })

  it('reports the total from the server, not the number of rows on this page', async () => {
    // `totalElements` is 57 while the page holds 2. A component deriving the
    // count from `items.length` says "2 student(s) found" and quietly tells the
    // admin their college has two students.
    serveStudents([studentRow(1, 'Arjun Kumar', 'SBU001'), studentRow(2, 'Priya Nair', 'SBU002')])
    server.use(
      http.get(`${API}/admin/students`, ({ request }) => {
        requests.push(new URL(request.url))
        return HttpResponse.json(
          pageOf([studentRow(1, 'Arjun Kumar', 'SBU001'), studentRow(2, 'Priya Nair', 'SBU002')], 57),
        )
      }),
    )

    renderWithProviders(<StudentsList />)

    expect(await screen.findByText(/57 student\(s\) found/)).toBeInTheDocument()
  })

  it('sends what was typed to the server as a search parameter', async () => {
    const user = userEvent.setup()
    serveStudents([studentRow(1, 'Arjun Kumar', 'SBU001')])

    renderWithProviders(<StudentsList />)
    await screen.findByText('SBU001')

    await user.type(screen.getByPlaceholderText(/search/i), 'priya')

    await waitFor(() => {
      expect(requests.at(-1)?.searchParams.get('search')).toBe('priya')
    })
  })

  it('renders a row that does not match the search box, because filtering is not its job', async () => {
    // The decisive test. The server is asked for "priya" and deliberately
    // answers with a student whose name and roll number contain no such text --
    // which is exactly what a real server does when it matches on a column the
    // table does not show, such as the email address. A component that filters
    // client-side drops this row and shows an empty table.
    const user = userEvent.setup()
    serveStudents([studentRow(7, 'Arjun Kumar', 'SBU001')])

    renderWithProviders(<StudentsList />)
    await screen.findByText('SBU001')

    await user.type(screen.getByPlaceholderText(/search/i), 'priya')

    await waitFor(() => {
      expect(requests.at(-1)?.searchParams.get('search')).toBe('priya')
    })

    expect(screen.getByText('SBU001')).toBeInTheDocument()
  })

  it('debounces a burst of keystrokes into far fewer requests than characters', async () => {
    const user = userEvent.setup()
    serveStudents([studentRow(1, 'Arjun Kumar', 'SBU001')])

    renderWithProviders(<StudentsList />)
    await screen.findByText('SBU001')

    const before = requests.length
    await user.type(screen.getByPlaceholderText(/search/i), 'database')

    await waitFor(() => {
      expect(requests.at(-1)?.searchParams.get('search')).toBe('database')
    })

    // Eight characters. Without the debounce this is eight more requests, and
    // they can land out of order -- leaving the list showing results for
    // "datab". The exact count depends on typing speed, so the assertion is the
    // property, not a number.
    expect(requests.length - before).toBeLessThan(8)
  })

  it('asks for a bounded page rather than the whole table', async () => {
    serveStudents([studentRow(1, 'Arjun Kumar', 'SBU001')])

    renderWithProviders(<StudentsList />)
    await screen.findByText('SBU001')

    const first = requests.at(0)
    expect(first?.searchParams.get('page')).toBe('0')
    // The backend clamps anything above 100; asking for an unbounded list was a
    // one-request denial of service before it did.
    expect(Number(first?.searchParams.get('size'))).toBeLessThanOrEqual(100)
  })

  it('shows the empty state rather than a bare table when the college has no students', async () => {
    serveStudents([])

    renderWithProviders(<StudentsList />)

    expect(await screen.findByText(/no students found/i)).toBeInTheDocument()
  })
})
