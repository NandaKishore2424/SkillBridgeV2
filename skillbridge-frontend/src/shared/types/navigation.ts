/**
 * Navigation-related types for layout components
 */

import type { UserRole } from '@/shared/types'

export interface NavItem {
  title: string
  href: string
  icon?: React.ComponentType<{ className?: string }>
  badge?: string | number
  children?: NavItem[]
}

/**
 * Was declared as `interface SidebarConfig { [key in UserRole]: NavItem[] }`,
 * which is not valid TypeScript -- mapped types must be type aliases, and as an
 * interface it silently degraded to an index signature TS could not check. That
 * is why Sidebar.tsx indexing it by UserRole raised an implicit-any error.
 */
export type SidebarConfig = Record<UserRole, NavItem[]>

export interface LayoutProps {
  children: React.ReactNode
}

