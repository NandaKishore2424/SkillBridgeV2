/**
 * The student's own view of a batch curriculum, topic by topic.
 *
 * The mirror of the trainer's grading screen at `/trainer/topics/{id}/grade`:
 * that one is a topic with a column of students, this one is a student with a
 * tree of topics. Both read the same `topic_progress` rows, so what a trainer
 * records here is what the student reads, including the comment.
 *
 * The server returns progress already nested as the curriculum, so nothing here
 * regroups a flat list -- and nothing here recomputes a percentage either. Each
 * level's `weightedPercent` is the server's, because a module's progress is not
 * the mean of its sub-modules' when they hold different numbers of topics, and
 * two places computing it differently is how a progress bar starts disagreeing
 * with the number printed beside it.
 */

import { useMemo } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  AlertCircle,
  ArrowRight,
  BookOpen,
  CalendarDays,
  MessageSquareQuote,
} from 'lucide-react'

import { AuthenticatedLayout, PageWrapper } from '@/shared/components/layout'
import { RoleGuard } from '@/shared/components/auth'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/shared/components/ui'
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from '@/shared/components/ui/accordion'
import { CardSkeleton, ListSkeleton } from '@/shared/components/ui/loading-skeleton'
import {
  getMyProgressDetail,
  getStudentBatches,
  type ProgressStatus,
  type SubmoduleProgressDetail,
  type TopicProgressDetail,
} from '@/api/student'
import { itemsOf } from '@/api/paging'

const STATUS_LABEL: Record<ProgressStatus, string> = {
  PENDING: 'Not started',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Completed',
  NEEDS_IMPROVEMENT: 'Needs work',
}

const STATUS_STYLE: Record<ProgressStatus, string> = {
  PENDING: 'bg-slate-100 text-slate-700',
  IN_PROGRESS: 'bg-blue-100 text-blue-800',
  COMPLETED: 'bg-green-100 text-green-800',
  NEEDS_IMPROVEMENT: 'bg-amber-100 text-amber-900',
}

/** A percentage the server computed. Clamped only against a malformed response. */
function ProgressBar({ percent, className = '' }: { percent: number; className?: string }) {
  const width = Math.max(0, Math.min(100, Number.isFinite(percent) ? percent : 0))
  return (
    <div
      className={`h-2 w-full overflow-hidden rounded-full bg-slate-100 ${className}`}
      role="progressbar"
      aria-valuenow={Math.round(width)}
      aria-valuemin={0}
      aria-valuemax={100}
    >
      <div className="h-full rounded-full bg-primary transition-all" style={{ width: `${width}%` }} />
    </div>
  )
}

function StatusPill({ status }: { status: ProgressStatus }) {
  return (
    <Badge variant="secondary" className={STATUS_STYLE[status]}>
      {STATUS_LABEL[status]}
    </Badge>
  )
}

function formatDate(value: string | null) {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toLocaleDateString()
}

function TopicRow({ topic }: { topic: TopicProgressDetail }) {
  const completed = formatDate(topic.completedAt)

  return (
    <li className="rounded-md border border-slate-200 p-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="font-medium">{topic.topicName}</p>
          {topic.topicDescription && (
            <p className="mt-0.5 text-sm text-muted-foreground">{topic.topicDescription}</p>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {topic.score !== null && (
            <span className="text-sm font-semibold tabular-nums">{topic.score}/100</span>
          )}
          <StatusPill status={topic.status} />
        </div>
      </div>

      {/* The trainer's comment sits with the topic it is about. Sending a
          student to a separate feedback screen to find out why a topic says
          "needs work" is the one thing this page exists to avoid. */}
      {topic.comment && (
        <div className="mt-3 flex gap-2 rounded-md bg-slate-50 p-3 text-sm">
          <MessageSquareQuote className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
          <div>
            <p className="whitespace-pre-wrap">{topic.comment}</p>
            {topic.gradedByTrainerName && (
              <p className="mt-1 text-xs text-muted-foreground">
                {topic.gradedByTrainerName}
                {completed ? ` · ${completed}` : ''}
              </p>
            )}
          </div>
        </div>
      )}

      {!topic.comment && topic.gradedByTrainerName && (
        <p className="mt-2 text-xs text-muted-foreground">
          Graded by {topic.gradedByTrainerName}
          {completed ? ` · ${completed}` : ''}
        </p>
      )}
    </li>
  )
}

function SubmoduleBlock({ submodule }: { submodule: SubmoduleProgressDetail }) {
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h4 className="font-medium">
          {submodule.submoduleName}
          {submodule.weekNumber !== null && (
            <span className="ml-2 text-xs font-normal text-muted-foreground">
              Week {submodule.weekNumber}
            </span>
          )}
        </h4>
        <span className="text-xs text-muted-foreground">
          {submodule.topicsCompleted}/{submodule.topicsTotal} topics ·{' '}
          {Math.round(submodule.weightedPercent)}%
        </span>
      </div>
      <ul className="space-y-2">
        {submodule.topics.map((topic) => (
          <TopicRow key={topic.topicId} topic={topic} />
        ))}
      </ul>
    </div>
  )
}

export default function StudentProgress() {
  // The chosen batch lives in the URL so the page is linkable and survives a
  // reload -- a student comparing two batches should be able to bookmark one.
  const [searchParams, setSearchParams] = useSearchParams()

  const { data: batches, isLoading: batchesLoading } = useQuery({
    queryKey: ['student', 'batches', 'enrolled'],
    queryFn: () => getStudentBatches({ size: 100 }),
    select: itemsOf,
  })

  const requestedId = Number(searchParams.get('batchId'))
  const batchId = useMemo(() => {
    if (!batches || batches.length === 0) return null
    const requested = batches.find((b) => b.id === requestedId)
    return (requested ?? batches[0]).id
  }, [batches, requestedId])

  const {
    data: progress,
    isLoading: progressLoading,
    error,
  } = useQuery({
    queryKey: ['student', 'progress', 'detail', batchId],
    queryFn: () => getMyProgressDetail(batchId as number),
    enabled: batchId !== null,
  })

  const modules = progress?.modules ?? []

  return (
    <RoleGuard allowedRoles={['STUDENT']}>
      <AuthenticatedLayout>
        <PageWrapper>
          <div className="space-y-6">
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div>
                <h1 className="text-2xl font-bold">My Progress</h1>
                <p className="text-sm text-muted-foreground">
                  Your curriculum, topic by topic, as your trainer has graded it.
                </p>
              </div>

              {batches && batches.length > 1 && (
                <Select
                  value={batchId === null ? undefined : String(batchId)}
                  onValueChange={(value) => setSearchParams({ batchId: value })}
                >
                  <SelectTrigger className="w-[260px]">
                    <SelectValue placeholder="Choose a batch" />
                  </SelectTrigger>
                  <SelectContent>
                    {batches.map((batch) => (
                      <SelectItem key={batch.id} value={String(batch.id)}>
                        {batch.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            </div>

            {batchesLoading && <CardSkeleton />}

            {!batchesLoading && batches && batches.length === 0 && (
              <Card>
                <CardContent className="flex flex-col items-center gap-3 py-12 text-center">
                  <BookOpen className="h-8 w-8 text-muted-foreground" />
                  <div>
                    <p className="font-medium">You are not enrolled in a batch yet</p>
                    <p className="text-sm text-muted-foreground">
                      Progress appears here once you join one.
                    </p>
                  </div>
                  <Button asChild>
                    <Link to="/student/dashboard">
                      Browse batches
                      <ArrowRight className="ml-2 h-4 w-4" />
                    </Link>
                  </Button>
                </CardContent>
              </Card>
            )}

            {error && (
              <Alert variant="destructive">
                <AlertCircle className="h-4 w-4" />
                <AlertDescription>
                  Could not load your progress for this batch. Try again in a moment.
                </AlertDescription>
              </Alert>
            )}

            {progressLoading && batchId !== null && <ListSkeleton />}

            {progress && (
              <>
                <Card>
                  <CardHeader>
                    <CardTitle>{progress.batchName}</CardTitle>
                    <CardDescription>
                      {progress.topicsCompleted} of {progress.topicsTotal} topics completed
                      {progress.averageScore !== null &&
                        ` · average score ${Math.round(progress.averageScore)}/100`}
                    </CardDescription>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    <div>
                      <div className="mb-1 flex items-baseline justify-between">
                        <span className="text-sm text-muted-foreground">Overall</span>
                        <span className="text-2xl font-bold tabular-nums">
                          {Math.round(progress.weightedPercent)}%
                        </span>
                      </div>
                      <ProgressBar percent={progress.weightedPercent} />
                      <p className="mt-1 text-xs text-muted-foreground">
                        Weighted: a topic in progress counts for part of a completed one.
                      </p>
                    </div>

                    <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                      {(
                        [
                          ['COMPLETED', progress.topicsCompleted],
                          ['IN_PROGRESS', progress.topicsInProgress],
                          ['NEEDS_IMPROVEMENT', progress.topicsNeedsWork],
                          ['PENDING', progress.topicsPending],
                        ] as [ProgressStatus, number][]
                      ).map(([status, count]) => (
                        <div key={status} className="rounded-md border border-slate-200 p-3">
                          <div className="text-xl font-bold tabular-nums">{count}</div>
                          <div className="mt-1">
                            <StatusPill status={status} />
                          </div>
                        </div>
                      ))}
                    </div>

                    {progress.lastActivityAt && (
                      <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
                        <CalendarDays className="h-3.5 w-3.5" />
                        Last graded {formatDate(progress.lastActivityAt)}
                      </p>
                    )}
                  </CardContent>
                </Card>

                <Card>
                  <CardHeader>
                    <CardTitle>Curriculum</CardTitle>
                    <CardDescription>
                      {modules.length} module{modules.length === 1 ? '' : 's'} ·{' '}
                      {progress.topicsTotal} topic{progress.topicsTotal === 1 ? '' : 's'}
                    </CardDescription>
                  </CardHeader>
                  <CardContent>
                    {modules.length === 0 ? (
                      <p className="py-8 text-center text-sm text-muted-foreground">
                        Your trainer has not published a syllabus for this batch yet.
                      </p>
                    ) : (
                      <Accordion
                        type="multiple"
                        // The first module open, so the page is never a wall of
                        // closed rows on arrival.
                        defaultValue={[String(modules[0].moduleId)]}
                        className="w-full"
                      >
                        {modules.map((module) => (
                          <AccordionItem key={module.moduleId} value={String(module.moduleId)}>
                            <AccordionTrigger className="hover:no-underline">
                              <div className="flex w-full flex-wrap items-center justify-between gap-3 pr-3 text-left">
                                <span className="font-medium">{module.moduleName}</span>
                                <span className="flex items-center gap-3">
                                  <ProgressBar percent={module.weightedPercent} className="w-24" />
                                  <span className="text-xs tabular-nums text-muted-foreground">
                                    {module.topicsCompleted}/{module.topicsTotal}
                                  </span>
                                </span>
                              </div>
                            </AccordionTrigger>
                            <AccordionContent className="space-y-5 pb-6">
                              {module.submodules.length === 0 ? (
                                <p className="text-sm text-muted-foreground">
                                  This module has no topics yet.
                                </p>
                              ) : (
                                module.submodules.map((submodule) => (
                                  <SubmoduleBlock key={submodule.submoduleId} submodule={submodule} />
                                ))
                              )}
                            </AccordionContent>
                          </AccordionItem>
                        ))}
                      </Accordion>
                    )}
                  </CardContent>
                </Card>
              </>
            )}
          </div>
        </PageWrapper>
      </AuthenticatedLayout>
    </RoleGuard>
  )
}
