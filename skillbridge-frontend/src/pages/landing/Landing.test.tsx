import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { renderWithProviders, signIn } from '@/test/render'
import { DASHBOARD_PATH } from '@/shared/auth/dashboardPath'
import type { UserRole } from '@/shared/types'

import { Landing } from './Landing'
import { FACTS } from './facts'

/**
 * The public page.
 *
 * Three things are worth a test here, and "it renders" is none of them.
 *
 * 1. **A signed-in person must never see it.** The previous version redirected
 *    from inside `useEffect`, which renders the whole marketing site and
 *    replaces it a frame later -- a visible flash of the public page every time
 *    somebody opens the root URL with a session. A declarative redirect cannot
 *    do that, and the only way to tell the two apart in a test is to assert
 *    that the marketing content never appeared at all.
 * 2. **Every route it offers must exist.** `App.test.tsx` covers that for the
 *    whole application; here it is the one thing a visitor can do.
 * 3. **The figures must come from `facts.ts`**, so that changing a number in
 *    one place changes it everywhere and `facts.test.ts` still guards it.
 */

function landmarkText() {
  return screen.getByRole('main').textContent ?? ''
}

describe('the landing page', () => {
  it('sends a signed-in person to their dashboard without drawing itself', () => {
    signIn({ role: 'COLLEGE_ADMIN' })

    const { container } = renderWithProviders(<Landing />)

    // Not "it eventually navigates": nothing of the public page may be painted
    // on the way, which is what an effect-based redirect cannot promise.
    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
  })

  it.each(Object.keys(DASHBOARD_PATH) as UserRole[])(
    'does not show itself to a signed-in %s either',
    (role) => {
      signIn({ role })

      const { container } = renderWithProviders(<Landing />)

      expect(container).toBeEmptyDOMElement()
    },
  )

  it('shows a visitor one way in, and it is the login route', () => {
    renderWithProviders(<Landing />)

    const signIns = screen.getAllByRole('link', { name: /sign in/i })
    expect(signIns.length).toBeGreaterThan(0)
    for (const link of signIns) {
      expect(link).toHaveAttribute('href', '/login')
    }
    // Invite-only: offering a route that does not exist is the bug this stops.
    expect(screen.queryByRole('link', { name: /sign up|register|create account/i })).toBeNull()
  })

  it('prints the corpus size and embedding width from facts.ts', () => {
    renderWithProviders(<Landing />)

    const text = landmarkText()
    expect(text).toContain(FACTS.corpusSize.toLocaleString())
    expect(text).toContain(String(FACTS.embeddingDimensions))
    expect(text).toContain(FACTS.embeddingModel)
    expect(text).toContain(FACTS.maxImportRows.toLocaleString())
  })

  it('claims nothing the application does not do', () => {
    renderWithProviders(<Landing />)

    // The page this replaced advertised placement and application tracking.
    // Neither exists: companies can be linked to a batch, and that is all.
    // A test is the only thing that stops the copy drifting back.
    const text = landmarkText().toLowerCase()
    for (const claim of ['track applications', 'placement tracking', 'job alerts', 'resume builder']) {
      expect(text, `the page claims "${claim}"`).not.toContain(claim)
    }
  })

  it('labels the sample report as a sample, for a screen reader too', () => {
    renderWithProviders(<Landing />)

    // The skills in the hero illustration are invented. Read out as a list
    // without that word, they sound like somebody's actual profile.
    const preview = screen.getByRole('img', { name: /sample skill-gap report/i })
    expect(preview).toBeInTheDocument()
    expect(within(preview).getByText('Sample')).toBeInTheDocument()
  })

  it('gives the page exactly one h1, and section headings under it', () => {
    renderWithProviders(<Landing />)

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getAllByRole('heading', { level: 2 }).length).toBeGreaterThanOrEqual(3)
  })

  it('points "How it works" at a section that is on the page', () => {
    renderWithProviders(<Landing />)

    const anchor = screen.getByRole('link', { name: /how it works/i })
    const target = anchor.getAttribute('href')?.replace('#', '')

    expect(target).toBeTruthy()
    // An in-page anchor to an id that does not exist scrolls nowhere and looks
    // like a broken button.
    expect(document.getElementById(target!)).not.toBeNull()
  })
})
