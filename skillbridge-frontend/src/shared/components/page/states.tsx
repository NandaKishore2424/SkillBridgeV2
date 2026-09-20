import { AlertCircle, SearchX } from 'lucide-react'
import type { ComponentType, ReactNode } from 'react'

import { Alert, AlertDescription, AlertTitle } from '@/shared/components/ui/alert'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { apiErrorMessage } from '@/lib/apiError'
import { cn } from '@/lib/utils'

/**
 * The three states every fetched page has, drawn the same way each time.
 *
 * Written down because the alternative is what this codebase had: some screens
 * showed a spinner, some showed nothing, some showed an empty table that reads
 * as "no results" while the request is still in flight -- and a 404 from the
 * API rendered as a generic red box that gave the person no idea whether the
 * thing was missing or the server was broken.
 */

export function DetailSkeleton({ className }: { className?: string }) {
  return (
    <div className={cn('space-y-6', className)} role="status" aria-label="Loading">
      <Skeleton className="h-8 w-64" />
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {[0, 1, 2, 3].map((index) => (
          <Skeleton key={index} className="h-24" />
        ))}
      </div>
      <Skeleton className="h-64" />
    </div>
  )
}

export function ErrorState({
  error,
  title = 'Could not load this page',
  fallback = 'Something went wrong. Try again in a moment.',
  action,
}: {
  error: unknown
  title?: string
  fallback?: string
  action?: ReactNode
}) {
  return (
    <Alert variant="destructive">
      <AlertCircle className="h-4 w-4" />
      <AlertTitle>{title}</AlertTitle>
      <AlertDescription className="space-y-3">
        {/* The API's own message, not a generic one: it is the difference
            between "we could not reach the server" and "that batch is full". */}
        <p>{apiErrorMessage(error, fallback)}</p>
        {action}
      </AlertDescription>
    </Alert>
  )
}

export function EmptyState({
  icon: Icon = SearchX,
  title,
  description,
  action,
  className,
}: {
  icon?: ComponentType<{ className?: string }>
  title: string
  description?: ReactNode
  action?: ReactNode
  className?: string
}) {
  return (
    <div className={cn('flex flex-col items-center justify-center px-6 py-14 text-center', className)}>
      <span className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-muted text-muted-foreground">
        <Icon className="h-6 w-6" />
      </span>
      <h3 className="text-base font-semibold">{title}</h3>
      {description && (
        <p className="mt-1 max-w-sm text-sm text-muted-foreground">{description}</p>
      )}
      {action && <div className="mt-5">{action}</div>}
    </div>
  )
}
