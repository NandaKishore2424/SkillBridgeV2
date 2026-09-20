/**
 * True when the API said 404.
 *
 * Its own module so that `states.tsx` exports nothing but components: Vite's
 * fast refresh gives up on a file that mixes the two, and editing an error
 * state during development would then reload the page instead of hot-swapping
 * it.
 *
 * A missing thing is not a failure to load, and the difference matters on
 * screen: "could not load this student" sends an admin to check their
 * connection for a student who is simply not there.
 */
export function isNotFound(error: unknown): boolean {
  return (error as { response?: { status?: number } })?.response?.status === 404
}
