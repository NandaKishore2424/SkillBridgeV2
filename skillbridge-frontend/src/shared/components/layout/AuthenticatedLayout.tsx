/**
 * AuthenticatedLayout Component
 * 
 * Wrapper component that:
 * - Uses Layout component
 * - Connects to AuthContext
 * - Shows sidebar for authenticated users
 * - Handles logout
 * 
 * Use this for all protected pages that need the full layout
 */

import { Layout } from './Layout'
import { useAuth } from '@/shared/hooks/useAuth'

interface AuthenticatedLayoutProps {
  children: React.ReactNode
  /** Show footer */
  showFooter?: boolean
}

export function AuthenticatedLayout({ children, showFooter = true }: AuthenticatedLayoutProps) {
  const { user, logout } = useAuth()

  // `User` carries no display name -- names live on the student/trainer profile,
  // not on the account. Reading user.name was always undefined and fell through
  // to the email local part, which is what actually renders.
  const userName = user?.email?.split('@')[0] || 'User'

  return (
    <Layout
      user={
        user
          ? {
              email: user.email,
              role: user.role,
              name: userName,
            }
          : undefined
      }
      onLogout={logout}
      showSidebar={!!user}
      showFooter={showFooter}
    >
      {children}
    </Layout>
  )
}

