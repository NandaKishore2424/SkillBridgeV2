/**
 * ProtectedRoute Component
 * 
 * Route guard that:
 * - Checks if user is authenticated
 * - Redirects to login if not authenticated
 * - Shows loading state while checking
 * - Renders children if authenticated
 */

import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '@/shared/hooks/useAuth'
import { PageWrapper } from '@/shared/components/layout'

interface ProtectedRouteProps {
  children: React.ReactNode
  /** Redirect path when not authenticated (default: /login) */
  redirectTo?: string
}

export function ProtectedRoute({ children, redirectTo = '/login' }: ProtectedRouteProps) {
  const { isAuthenticated, isLoading } = useAuth()
  const location = useLocation()

  // Show loading state while checking authentication
  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-center">
          <div className="inline-block h-8 w-8 animate-spin rounded-full border-4 border-solid border-primary border-r-transparent"></div>
          <p className="mt-4 text-muted-foreground">Loading...</p>
        </div>
      </div>
    )
  }

  // Redirect to login if not authenticated
  if (!isAuthenticated) {
    return <Navigate to={redirectTo} state={{ from: location }} replace />
  }

  // Render children if authenticated
  return <>{children}</>
}

