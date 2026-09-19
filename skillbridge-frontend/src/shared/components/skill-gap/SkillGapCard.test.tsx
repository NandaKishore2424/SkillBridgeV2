import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'

import { API } from '@/test/handlers'
import { renderWithProviders, signIn } from '@/test/render'
import { server } from '@/test/server'

import { MySkillGapCard } from './SkillGapCard'

/**
 * The skill-gap card against the backend's answers (SkillGapReportTest):
 * 204 before any analysis, the contract document after one, and 202 for
 * "Analyse again", after which the report changes once the AI service has run.
 */

function report(analyzedAt: string, missing: string[]) {
  return {
    status: 'SUCCESS',
    analyzedAt,
    studentSkills: ['java', 'sql'],
    matchedJobs: [{ title: 'Data Engineer', company: 'Acme', similarity: 0.624, matchedSkills: ['sql'], missingSkills: missing }],
    missingSkills: missing,
  }
}

let current: ReturnType<typeof report> | null
let refreshes: number

beforeEach(() => {
  signIn({ role: 'STUDENT' })
  current = null
  refreshes = 0
  server.use(
    http.get(`${API}/students/me/skill-gap`, () =>
      current === null ? new HttpResponse(null, { status: 204 }) : HttpResponse.json(current)),
    http.post(`${API}/students/me/skill-gap/refresh`, () => {
      refreshes += 1
      // The AI service answers a moment later.
      setTimeout(() => { current = report('2026-09-19T16:20:00', ['spark']) }, 50)
      return new HttpResponse(null, { status: 202 })
    }),
  )
})

describe('MySkillGapCard', () => {
  it('says there is no analysis yet on a 204, rather than showing an empty report', async () => {
    renderWithProviders(<MySkillGapCard />)

    expect(await screen.findByText(/No analysis yet/)).toBeInTheDocument()
  })

  it('shows the skills to learn and each job with its similarity as a percentage', async () => {
    current = report('2026-09-19T16:10:00', ['python'])
    renderWithProviders(<MySkillGapCard />)

    expect(await screen.findByText('Data Engineer')).toBeInTheDocument()
    expect(screen.getByText('62%')).toBeInTheDocument()
    expect(screen.getByText('python')).toBeInTheDocument()
  })

  it('after "Analyse again", waits and then shows the new report', async () => {
    current = report('2026-09-19T16:10:00', ['python'])
    renderWithProviders(<MySkillGapCard />)
    await screen.findByText('python')

    await userEvent.click(screen.getByRole('button', { name: /analyse again/i }))

    expect(await screen.findByRole('button', { name: /analysing/i })).toBeDisabled()
    await waitFor(() => expect(screen.getByText('spark')).toBeInTheDocument(), { timeout: 5000 })
    expect(screen.getByRole('button', { name: /analyse again/i })).toBeEnabled()
    expect(refreshes).toBe(1)
  })
})
