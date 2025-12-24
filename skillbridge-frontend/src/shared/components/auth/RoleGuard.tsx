/**
 * RoleGuard Component
 * 
 * Component-level role-based access control
 * - Only renders children if user has one of the allowed roles
 * - Shows 403 message or redirects if access denied
 * - Useful for conditionally rendering UI elements based on role
 */

import { Navigate } from 'react-router-dom'
import { useAuth } from '@/shared/hooks/useAuth'
import { UserRole } from '@/shared/types'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/components/ui'

interface RoleGuardProps {
  children: React.ReactNode
  /** Allowed roles that can access this content */
  allowedRoles: UserRole[]
  /** Redirect path when access denied (default: show 403 message) */
  redirectTo?: string
  /** Show 403 message instead of redirecting */
  showMessage?: boolean
}

export function RoleGuard({
  children,
  allowedRoles,
  redirectTo,
  showMessage = true,
}: RoleGuardProps) {
  const { user, isLoading } = useAuth()

  // Show loading state while checking
  if (isLoading) {
    return (
      <div className="flex items-center justify-center p-8">
        <div className="text-center">
          <div className="inline-block h-6 w-6 animate-spin rounded-full border-4 border-solid border-primary border-r-transparent"></div>
        </div>
      </div>
    )
  }

  // Check if user has required role
  if (!user || !allowedRoles.includes(user.role)) {
    // Redirect if redirectTo is provided
    if (redirectTo) {
      return <Navigate to={redirectTo} replace />
    }

    // Show 403 message if showMessage is true
    if (showMessage) {
      return (
        <div className="flex items-center justify-center min-h-[400px] p-8">
          <Card className="w-full max-w-md">
            <CardHeader>
              <CardTitle className="text-destructive">Access Denied</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-muted-foreground">
                You don't have permission to access this resource. Required roles:{' '}
                {allowedRoles.join(', ')}
              </p>
            </CardContent>
          </Card>
        </div>
      )
    }

    // Return null if neither redirect nor message
    return null
  }

  // Render children if user has required role
  return <>{children}</>
}

