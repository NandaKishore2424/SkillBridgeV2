/**
 * PageWrapper Component
 * 
 * Consistent page container with:
 * - Standardized padding/margins
 * - Max-width container
 * - Optional breadcrumbs support
 */

import { cn } from '@/lib/utils'
import { ReactNode } from 'react'

interface PageWrapperProps {
  /** Page content */
  children: ReactNode
  /** Additional CSS classes */
  className?: string
  /** Max width variant */
  maxWidth?: 'sm' | 'md' | 'lg' | 'xl' | '2xl' | 'full'
  /** Show padding */
  padding?: boolean
}

const maxWidthClasses = {
  sm: 'max-w-screen-sm',
  md: 'max-w-screen-md',
  lg: 'max-w-screen-lg',
  xl: 'max-w-screen-xl',
  '2xl': 'max-w-screen-2xl',
  full: 'max-w-full',
}

export function PageWrapper({
  children,
  className,
  maxWidth = 'xl',
  padding = true,
}: PageWrapperProps) {
  return (
    <div
      className={cn(
        'mx-auto w-full',
        maxWidthClasses[maxWidth],
        padding && 'px-4 py-6 sm:px-6 lg:px-8',
        className
      )}
    >
      {children}
    </div>
  )
}

