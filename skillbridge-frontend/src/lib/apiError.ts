/**
 * The message to show for a failed API call.
 *
 * Every error from the API has the ErrorResponse shape: `error` (a stable code),
 * `message`, and optional `details`. For WEAK_PASSWORD, the details hold every
 * rule the password broke (`rule1`, `rule2`, ...), and all of them are shown, so
 * the user fixes the password in one go.
 */
export function apiErrorMessage(err: unknown, fallback: string): string {
  const data = (err as { response?: { data?: { error?: string; message?: string; details?: Record<string, string> } } })
    ?.response?.data
  if (!data?.message) {
    return fallback
  }
  if (data.error === 'WEAK_PASSWORD' && data.details) {
    return [data.message, ...Object.values(data.details)].join(' ')
  }
  return data.message
}
