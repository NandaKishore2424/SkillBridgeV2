import type { UserRole } from '@/shared/types'

/**
 * Where a role lands after signing in.
 *
 * One table, because there were four copies of this switch -- in the landing
 * page, the header's logo link, the sidebar, and `AuthContext` -- and a fifth
 * role would have had to be added to all four. A `Record<UserRole, string>`
 * also means adding a role to the union fails to compile until its dashboard
 * exists, which a `switch` with a `default` never does.
 */
export const DASHBOARD_PATH: Record<UserRole, string> = {
  SYSTEM_ADMIN: '/admin/dashboard',
  COLLEGE_ADMIN: '/admin/college-admin/dashboard',
  TRAINER: '/trainer/dashboard',
  STUDENT: '/student/dashboard',
}

/**
 * The dashboard for a role, or the public page when the role is unknown.
 *
 * An unknown role reaches here only from a token claim, which is data from
 * outside; falling back to `/` is the safe direction, because every dashboard
 * is behind its own `RoleGuard` anyway.
 */
export function dashboardPathFor(role: string | undefined | null): string {
  return (role && DASHBOARD_PATH[role as UserRole]) || '/'
}
