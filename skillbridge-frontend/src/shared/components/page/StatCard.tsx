import type { ComponentType, ReactNode } from 'react'

import { Card, CardContent } from '@/shared/components/ui/card'
import { cn } from '@/lib/utils'

/**
 * One number, with a word for what it counts.
 *
 * Four dashboards had four copies of this, each slightly different -- two put
 * the icon on the right, one had a hover shadow, one made the value `text-2xl`
 * and another `text-3xl`. The number is the point, so it is the biggest thing
 * in the card and it is `tabular-nums`: without that, a figure that ticks from
 * 9 to 10 shifts every digit beside it.
 */
export function StatCard({
  label,
  value,
  hint,
  icon: Icon,
  className,
}: {
  label: string
  value: ReactNode
  hint?: ReactNode
  icon?: ComponentType<{ className?: string }>
  className?: string
}) {
  return (
    <Card className={cn('transition-shadow hover:shadow-sm', className)}>
      <CardContent className="flex items-start justify-between gap-3 p-5">
        <div className="min-w-0">
          <p className="text-sm font-medium text-muted-foreground">{label}</p>
          <p className="mt-2 text-3xl font-bold tabular-nums tracking-tight">{value}</p>
          {hint && <p className="mt-1 text-xs text-muted-foreground">{hint}</p>}
        </div>
        {Icon && (
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
            <Icon className="h-5 w-5" />
          </span>
        )}
      </CardContent>
    </Card>
  )
}
