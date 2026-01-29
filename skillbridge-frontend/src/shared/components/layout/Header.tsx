/**
 * Header Component
 * 
 * Top navigation bar with:
 * - Logo/Brand
 * - User menu dropdown (avatar, name, role, logout)
 * - Responsive hamburger menu for mobile
 */

import { Link } from 'react-router-dom'
import { Menu, X, LogOut, User } from 'lucide-react'
import { Button } from '@/shared/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/shared/components/ui/dropdown-menu'
import { Avatar, AvatarFallback, AvatarImage } from '@/shared/components/ui/avatar'
import { Badge } from '@/shared/components/ui/badge'
import { cn } from '@/lib/utils'

interface HeaderProps {
  /** Whether sidebar is open (for mobile) */
  sidebarOpen?: boolean
  /** Toggle sidebar function (for mobile) */
  onSidebarToggle?: () => void
  /** User information */
  user?: {
    email: string
    role: string
    name?: string
  }
  /** Logout handler */
  onLogout?: () => void
  /** Show sidebar toggle button */
  showSidebarToggle?: boolean
}

export function Header({
  sidebarOpen = false,
  onSidebarToggle,
  user,
  onLogout,
  showSidebarToggle = false,
}: HeaderProps) {
  const getUserInitials = (email: string): string => {
    return email
      .split('@')[0]
      .split('.')
      .map((n) => n[0])
      .join('')
      .toUpperCase()
      .slice(0, 2)
  }

  const getRoleLabel = (role: string): string => {
    const roleMap: Record<string, string> = {
      SYSTEM_ADMIN: 'System Admin',
      COLLEGE_ADMIN: 'College Admin',
      TRAINER: 'Trainer',
      STUDENT: 'Student',
    }
    return roleMap[role] || role
  }

  return (
    <header className="sticky top-0 z-50 w-full border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div className="container flex h-16 items-center justify-between px-4">
        {/* Left: Logo and Sidebar Toggle */}
        <div className="flex items-center gap-4">
          {showSidebarToggle && (
            <Button
              variant="ghost"
              size="icon"
              className="lg:hidden"
              onClick={onSidebarToggle}
              aria-label="Toggle sidebar"
            >
              {sidebarOpen ? (
                <X className="h-5 w-5" />
              ) : (
                <Menu className="h-5 w-5" />
              )}
            </Button>
          )}

          {/* Logo - Navigate to dashboard if logged in, home if not */}
          <Link
            to={
              user
                ? user.role === 'SYSTEM_ADMIN'
                  ? '/admin/dashboard'
                  : user.role === 'COLLEGE_ADMIN'
                    ? '/admin/college-admin/dashboard'
                    : user.role === 'TRAINER'
                      ? '/trainer/dashboard'
                      : user.role === 'STUDENT'
                        ? '/student/dashboard'
                        : '/'
                : '/'
            }
            className="flex items-center gap-2"
          >
            <div className="flex items-center justify-center w-8 h-8 rounded-md bg-primary text-primary-foreground font-bold">
              SB
            </div>
            <span className="text-xl font-bold text-foreground hidden sm:inline-block">
              SkillBridge
            </span>
          </Link>
        </div>

        {/* Right: User Menu */}
        <div className="flex items-center gap-4">
          {user ? (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="ghost"
                  className="flex items-center gap-2 h-auto py-1.5 px-2 hover:bg-accent"
                >
                  <Avatar className="h-8 w-8">
                    <AvatarImage src="" alt={user.email} />
                    <AvatarFallback className="text-xs">
                      {getUserInitials(user.email)}
                    </AvatarFallback>
                  </Avatar>
                  <div className="flex flex-col items-start hidden sm:flex">
                    <span className="text-sm font-medium leading-none">
                      {user.name || user.email.split('@')[0]}
                    </span>
                    <Badge variant="secondary" className="text-xs mt-0.5">
                      {getRoleLabel(user.role)}
                    </Badge>
                  </div>
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-56">
                <DropdownMenuLabel>
                  <div className="flex flex-col space-y-1">
                    <p className="text-sm font-medium leading-none">
                      {user.name || user.email.split('@')[0]}
                    </p>
                    <p className="text-xs leading-none text-muted-foreground">
                      {user.email}
                    </p>
                  </div>
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem asChild>
                  <Link to="/profile" className="flex items-center gap-2 cursor-pointer">
                    <User className="h-4 w-4" />
                    <span>Profile</span>
                  </Link>
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  onClick={onLogout}
                  className="text-destructive focus:text-destructive cursor-pointer"
                >
                  <LogOut className="h-4 w-4 mr-2" />
                  <span>Logout</span>
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          ) : (
            <div className="flex items-center gap-2">
              <Button variant="ghost" asChild>
                <Link to="/login">Login</Link>
              </Button>
              <Button asChild>
                <Link to="/register">Register</Link>
              </Button>
            </div>
          )}
        </div>
      </div>
    </header>
  )
}

