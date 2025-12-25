/**
 * Layout Component
 * 
 * Main layout wrapper that combines:
 * - Header
 * - Sidebar (role-based)
 * - Main content area
 * - Footer
 * 
 * Manages responsive sidebar state and provides consistent layout structure
 */

import { useState, useEffect } from 'react'
import { cn } from '@/lib/utils'
import { Header } from './Header'
import { Sidebar } from './Sidebar'
import { Footer } from './Footer'
import type { UserRole } from '@/shared/types'

interface LayoutProps {
  /** Page content */
  children: React.ReactNode
  /** User information for header */
  user?: {
    email: string
    role: UserRole
    name?: string
  }
  /** Logout handler */
  onLogout?: () => void
  /** Show sidebar (based on authentication) */
  showSidebar?: boolean
  /** Show footer */
  showFooter?: boolean
}

export function Layout({
  children,
  user,
  onLogout,
  showSidebar = false,
  showFooter = true,
}: LayoutProps) {
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [isMobile, setIsMobile] = useState(false)

  // Handle responsive sidebar
  useEffect(() => {
    const checkMobile = () => {
      setIsMobile(window.innerWidth < 1024) // lg breakpoint
      if (window.innerWidth >= 1024) {
        setSidebarOpen(false) // Close sidebar on desktop resize
      }
    }

    checkMobile()
    window.addEventListener('resize', checkMobile)
    return () => window.removeEventListener('resize', checkMobile)
  }, [])

  const toggleSidebar = () => {
    setSidebarOpen((prev) => !prev)
  }

  const closeSidebar = () => {
    setSidebarOpen(false)
  }

  return (
    <div className="min-h-screen flex flex-col bg-background">
      {/* Header */}
      <Header
        sidebarOpen={sidebarOpen}
        onSidebarToggle={toggleSidebar}
        user={user}
        onLogout={onLogout}
        showSidebarToggle={showSidebar}
      />

      {/* Main Content Area */}
      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar - Single responsive sidebar */}
        {showSidebar && user?.role && (
          <Sidebar
            role={user.role}
            open={sidebarOpen}
            onClose={closeSidebar}
            mobile={isMobile}
          />
        )}

        {/* Main Content */}
        <main
          className={cn(
            'flex-1 overflow-y-auto',
            showSidebar && 'lg:ml-64' // Offset for sidebar width
          )}
        >
          {children}
        </main>
      </div>

      {/* Footer */}
      {showFooter && <Footer />}
    </div>
  )
}

