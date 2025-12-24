/**
 * Navigation-related types for layout components
 */

import { UserRole } from './index'

export interface NavItem {
  title: string
  href: string
  icon?: React.ComponentType<{ className?: string }>
  badge?: string | number
  children?: NavItem[]
}

export interface SidebarConfig {
  [key in UserRole]: NavItem[]
}

export interface LayoutProps {
  children: React.ReactNode
}

