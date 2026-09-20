import { ChevronLeft } from 'lucide-react'
import { Link } from 'react-router-dom'
import type { ReactNode } from 'react'

import { cn } from '@/lib/utils'

interface PageHeaderProps {
  title: ReactNode
  description?: ReactNode
  /** Where "back" goes. A page reached by a link should always offer one. */
  backTo?: { href: string; label: string }
  /** Buttons, right-aligned on a wide screen and stacked under the title on a phone. */
  actions?: ReactNode
  /** Badges or metadata shown under the title. */
  children?: ReactNode
  className?: string
}

/**
 * The top of every page, in one place.
 *
 * Twelve screens each had their own `<div><h1 className="text-3xl font-bold
 * tracking-tight">`, which is how a heading ends up three different sizes
 * depending on which page you are on. Each page also decided for itself whether
 * to offer a way back; a detail page reached from a list and offering none is
 * the commonest small cruelty in an admin UI.
 */
export function PageHeader({
  title,
  description,
  backTo,
  actions,
  children,
  className,
}: PageHeaderProps) {
  return (
    <div className={cn('space-y-4', className)}>
      {backTo && (
        <Link
          to={backTo.href}
          className="inline-flex items-center gap-1 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
        >
          <ChevronLeft className="h-4 w-4" />
          {backTo.label}
        </Link>
      )}

      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0 space-y-1">
          <h1 className="truncate text-2xl font-bold tracking-tight sm:text-3xl">{title}</h1>
          {description && <p className="text-muted-foreground">{description}</p>}
          {children}
        </div>
        {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </div>
  )
}
