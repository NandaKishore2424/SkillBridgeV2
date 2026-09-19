import { describe, expect, it } from 'vitest'

import { apiErrorMessage } from './apiError'

const axiosError = (data: unknown) => ({ response: { data } })

describe('apiErrorMessage', () => {
  it('shows the server message', () => {
    expect(apiErrorMessage(axiosError({ error: 'CONFLICT', message: 'Email already exists' }), 'fallback'))
      .toBe('Email already exists')
  })

  it('lists every broken password rule, so the user fixes the password once', () => {
    const message = apiErrorMessage(axiosError({
      error: 'WEAK_PASSWORD',
      message: 'The new password does not meet the password policy.',
      details: { rule1: 'Use at least 15 characters.', rule2: 'Do not build the password from your email address.' },
    }), 'fallback')
    expect(message).toContain('at least 15')
    expect(message).toContain('email address')
  })

  it('falls back when the response has no message (network error, CORS)', () => {
    expect(apiErrorMessage(new Error('Network Error'), 'Could not reach the server')).toBe('Could not reach the server')
  })
})
