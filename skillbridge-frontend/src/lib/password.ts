import { z } from 'zod'

/**
 * The client-side half of the backend's PasswordPolicy.
 *
 * Length is the rule, as NIST SP 800-63B recommends: at least 15 characters (its
 * minimum for a password that is the only factor), at most 128, and no "one
 * uppercase, one digit, one symbol" composition rules, which push people towards
 * `Password1!`. The server also refuses passwords built from the email address or
 * on a common-password list; those come back as a WEAK_PASSWORD error, and
 * apiErrorMessage shows them.
 */
export const PASSWORD_MIN_LENGTH = 15
export const PASSWORD_MAX_LENGTH = 128

export const passwordSchema = z
  .string()
  .min(PASSWORD_MIN_LENGTH, `Use at least ${PASSWORD_MIN_LENGTH} characters. A phrase of a few words is easiest to remember.`)
  .max(PASSWORD_MAX_LENGTH, `Use at most ${PASSWORD_MAX_LENGTH} characters.`)
