import type { ReactNode } from 'react'

import { cn } from '@/lib/utils'

/**
 * Label-and-value pairs, as a real `<dl>`.
 *
 * A grid of divs carries none of the relationship, so a screen reader reads
 * "Roll number" and "SBU001" as two unrelated strings. The em dash for an
 * absent value is deliberate and consistent: a blank cell is indistinguishable
 * from a rendering bug.
 */
export function DetailList({
  items,
  columns = 2,
  className,
}: {
  items: Array<{ label: string; value: ReactNode }>
  columns?: 2 | 3
  className?: string
}) {
  return (
    <dl
      className={cn(
        'grid gap-x-8 gap-y-5',
        columns === 3 ? 'sm:grid-cols-2 lg:grid-cols-3' : 'sm:grid-cols-2',
        className,
      )}
    >
      {items.map(({ label, value }) => (
        <div key={label} className="min-w-0">
          <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {label}
          </dt>
          <dd className="mt-1 break-words text-sm font-medium">
            {value === null || value === undefined || value === '' ? (
              <span className="text-muted-foreground">&mdash;</span>
            ) : (
              value
            )}
          </dd>
        </div>
      ))}
    </dl>
  )
}
