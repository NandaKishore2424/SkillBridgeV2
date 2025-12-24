/**
 * Sidebar Component
 * 
 * Role-based side navigation with:
 * - Collapsible sections
 * - Active route highlighting
 * - Different menus for different roles
 * - Responsive (collapsible on mobile)
 */

import { Link, useLocation } from 'react-router-dom'
import { cn } from '@/lib/utils'
import { ScrollArea } from '@/shared/components/ui'
import { UserRole } from '@/shared/types'
import { NavItem, SidebarConfig } from '@/shared/types/navigation'
import {
  LayoutDashboard,
  Building2,
  Users,
  GraduationCap,
  Briefcase,
  BookOpen,
  UserCog,
  PlusCircle,
  Menu,
} from 'lucide-react'

// Icon mapping for navigation items
const iconMap: Record<string, React.ComponentType<{ className?: string }>> = {
  Dashboard: LayoutDashboard,
  Colleges: Building2,
  'Create College': PlusCircle,
  'Create Admin': UserCog,
  Batches: BookOpen,
  Companies: Briefcase,
  Trainers: Users,
  Students: GraduationCap,
  'My Batches': BookOpen,
  'My Progress': LayoutDashboard,
  Placements: Briefcase,
}

// Sidebar configuration for each role
const sidebarConfig: SidebarConfig = {
  SYSTEM_ADMIN: [
    {
      title: 'Dashboard',
      href: '/admin/colleges',
      icon: LayoutDashboard,
    },
    {
      title: 'Colleges',
      href: '/admin/colleges',
      icon: Building2,
    },
    {
      title: 'Create College',
      href: '/admin/colleges/create',
      icon: PlusCircle,
    },
    {
      title: 'Create Admin',
      href: '/admin/admins/create',
      icon: UserCog,
    },
  ],
  COLLEGE_ADMIN: [
    {
      title: 'Dashboard',
      href: '/admin/dashboard',
      icon: LayoutDashboard,
    },
    {
      title: 'Batches',
      href: '/admin/batches',
      icon: BookOpen,
    },
    {
      title: 'Companies',
      href: '/admin/companies',
      icon: Briefcase,
    },
    {
      title: 'Trainers',
      href: '/admin/trainers',
      icon: Users,
    },
    {
      title: 'Students',
      href: '/admin/students',
      icon: GraduationCap,
    },
  ],
  TRAINER: [
    {
      title: 'Dashboard',
      href: '/trainer/dashboard',
      icon: LayoutDashboard,
    },
    {
      title: 'My Batches',
      href: '/trainer/batches',
      icon: BookOpen,
    },
    {
      title: 'Students',
      href: '/trainer/students',
      icon: GraduationCap,
    },
  ],
  STUDENT: [
    {
      title: 'Dashboard',
      href: '/student/dashboard',
      icon: LayoutDashboard,
    },
    {
      title: 'My Batches',
      href: '/student/batches',
      icon: BookOpen,
    },
    {
      title: 'My Progress',
      href: '/student/progress',
      icon: LayoutDashboard,
    },
    {
      title: 'Placements',
      href: '/student/placements',
      icon: Briefcase,
    },
  ],
}

interface SidebarProps {
  /** User role to determine navigation items */
  role?: UserRole
  /** Whether sidebar is open (for mobile) */
  open?: boolean
  /** Close sidebar function (for mobile) */
  onClose?: () => void
  /** Show on mobile */
  mobile?: boolean
}

export function Sidebar({ role, open = true, onClose, mobile = false }: SidebarProps) {
  const location = useLocation()

  if (!role) {
    return null
  }

  const navItems = sidebarConfig[role] || []

  const isActive = (href: string): boolean => {
    if (href === location.pathname) return true
    // Check if current path starts with href (for nested routes)
    if (location.pathname.startsWith(href) && href !== '/') return true
    return false
  }

  const NavLink = ({ item }: { item: NavItem }) => {
    const Icon = item.icon || iconMap[item.title] || Menu
    const active = isActive(item.href)

    return (
      <Link
        to={item.href}
        onClick={mobile ? onClose : undefined}
        className={cn(
          'flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
          active
            ? 'bg-primary text-primary-foreground'
            : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground'
        )}
      >
        <Icon className="h-4 w-4" />
        <span>{item.title}</span>
        {item.badge && (
          <span className="ml-auto rounded-full bg-primary px-2 py-0.5 text-xs text-primary-foreground">
            {item.badge}
          </span>
        )}
      </Link>
    )
  }

  return (
    <>
      {/* Mobile Overlay */}
      {mobile && open && (
        <div
          className="fixed inset-0 z-40 bg-black/50 lg:hidden"
          onClick={onClose}
          aria-hidden="true"
        />
      )}

      {/* Sidebar */}
      <aside
        className={cn(
          'fixed left-0 top-16 z-40 h-[calc(100vh-4rem)] w-64 border-r bg-background transition-transform duration-300 lg:translate-x-0',
          mobile && !open && '-translate-x-full',
          mobile && open && 'translate-x-0',
          !mobile && 'sticky'
        )}
      >
        <ScrollArea className="h-full">
          <nav className="flex flex-col gap-1 p-4">
            {navItems.map((item) => (
              <NavLink key={item.href} item={item} />
            ))}
          </nav>
        </ScrollArea>
      </aside>
    </>
  )
}

