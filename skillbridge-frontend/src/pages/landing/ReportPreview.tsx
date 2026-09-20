import { ArrowUpRight, Sparkles } from 'lucide-react'

import { Badge } from '@/shared/components/ui/badge'
import { cn } from '@/lib/utils'

/**
 * The hero's illustration: a skill-gap report, drawn rather than fetched.
 *
 * It is the screen worth showing, and on the public page there is no student to
 * show it for. So this is a picture of one -- labelled as a sample in the
 * accessible name, not only in small print, because a screen-reader user
 * otherwise hears a list of skills as though they were somebody's.
 *
 * It renders from the same tokens as the real card. When the palette changes
 * this changes with it, which is the only way an illustration of a product
 * stays a picture of that product.
 */

const SAMPLE = {
  student: 'Sample student',
  matched: [
    { title: 'Backend Engineer', company: 'Zeta Systems', similarity: 0.62 },
    { title: 'Data Platform Engineer', company: 'Northwind', similarity: 0.58 },
    { title: 'Java Developer', company: 'Corvus Labs', similarity: 0.55 },
  ],
  has: ['java', 'spring', 'sql', 'git'],
  missing: ['kafka', 'docker', 'aws', 'kubernetes'],
}

export function ReportPreview({ className }: { className?: string }) {
  return (
    <div
      className={cn(
        'rounded-2xl border bg-card p-5 shadow-xl shadow-primary/5 sm:p-6',
        className,
      )}
      role="img"
      aria-label="Sample skill-gap report: four skills on file, four missing, matched against three job descriptions"
    >
      <div aria-hidden="true">
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10 text-primary">
              <Sparkles className="h-4 w-4" />
            </span>
            <div>
              <p className="text-sm font-semibold leading-none">Skill gap</p>
              <p className="mt-1 text-xs text-muted-foreground">{SAMPLE.student}</p>
            </div>
          </div>
          <Badge variant="accent">Sample</Badge>
        </div>

        <div className="mt-5 space-y-2">
          <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Closest job descriptions
          </p>
          {SAMPLE.matched.map((job) => (
            <div
              key={job.title}
              className="flex items-center justify-between gap-3 rounded-lg border bg-background/60 px-3 py-2"
            >
              <div className="min-w-0">
                <p className="truncate text-sm font-medium">{job.title}</p>
                <p className="truncate text-xs text-muted-foreground">{job.company}</p>
              </div>
              <div className="flex shrink-0 items-center gap-2">
                {/* The bar is the number, so the two cannot disagree. */}
                <span className="hidden h-1.5 w-16 overflow-hidden rounded-full bg-muted sm:block">
                  <span
                    className="block h-full rounded-full bg-primary"
                    style={{ width: `${Math.round(job.similarity * 100)}%` }}
                  />
                </span>
                <span className="text-xs font-semibold tabular-nums">
                  {job.similarity.toFixed(2)}
                </span>
              </div>
            </div>
          ))}
        </div>

        <div className="mt-5 grid gap-4 sm:grid-cols-2">
          <div>
            <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              On file
            </p>
            <div className="mt-2 flex flex-wrap gap-1.5">
              {SAMPLE.has.map((skill) => (
                <Badge key={skill} variant="secondary" className="font-normal">
                  {skill}
                </Badge>
              ))}
            </div>
          </div>
          <div>
            <p className="flex items-center gap-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">
              Missing
              <ArrowUpRight className="h-3 w-3" />
            </p>
            <div className="mt-2 flex flex-wrap gap-1.5">
              {SAMPLE.missing.map((skill) => (
                <Badge
                  key={skill}
                  variant="outline"
                  className="border-warning/40 bg-warning/10 font-normal text-foreground"
                >
                  {skill}
                </Badge>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
