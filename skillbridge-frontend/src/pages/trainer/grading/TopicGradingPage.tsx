import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, CheckCircle2, Loader2 } from 'lucide-react'

import { Button } from '@/shared/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/components/ui/card'
import { Checkbox } from '@/shared/components/ui/checkbox'
import { Input } from '@/shared/components/ui/input'
import { Label } from '@/shared/components/ui/label'
import { Badge } from '@/shared/components/ui/badge'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/shared/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/shared/components/ui/table'
import {
  bulkGrade, getGradingGrid, type GradingGridRow, type ProgressStatus,
} from '@/api/trainer'

const STATUSES: { value: ProgressStatus; label: string }[] = [
  { value: 'PENDING', label: 'Pending' },
  { value: 'IN_PROGRESS', label: 'In progress' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'NEEDS_IMPROVEMENT', label: 'Needs improvement' },
]

const STATUS_STYLE: Record<ProgressStatus, string> = {
  PENDING: 'bg-slate-100 text-slate-700',
  IN_PROGRESS: 'bg-blue-100 text-blue-800',
  COMPLETED: 'bg-green-100 text-green-800',
  NEEDS_IMPROVEMENT: 'bg-amber-100 text-amber-900',
}

/**
 * The trainer's grading grid for one topic.
 *
 * The API for this has existed since Phase 04 and no screen ever called it. The
 * reason turned out to be that it could not be rendered: the grid returned rows
 * that named the *topic* rather than the student, so every row looked identical
 * and there was no `studentId` to send back. That was fixed on 2026-09-10; this
 * is the screen it was for.
 *
 * **Select students, set an outcome once, save once.** Phase 04's acceptance
 * criterion is that the grid "saves in one bulk request", and that is a
 * correctness property rather than a nicety: the server applies a bulk grade in
 * one transaction, so a class either grades or does not. Grading row by row
 * would be N round trips to a database in another region and N chances to leave
 * half a class marked.
 */
export default function TopicGradingPage() {
  const { topicId } = useParams<{ topicId: string }>()
  const id = Number(topicId)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [status, setStatus] = useState<ProgressStatus>('COMPLETED')
  const [score, setScore] = useState('')
  const [comment, setComment] = useState('')
  const [result, setResult] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const { data, isLoading } = useQuery({
    queryKey: ['grading-grid', id],
    queryFn: () => getGradingGrid(id, 0, 200),
    enabled: Number.isFinite(id) && id > 0,
  })

  const rows: GradingGridRow[] = useMemo(() => data?.items ?? [], [data])
  const allSelected = rows.length > 0 && selected.size === rows.length

  const toggle = (studentId: number) =>
    setSelected((prev) => {
      const next = new Set(prev)
      next.has(studentId) ? next.delete(studentId) : next.add(studentId)
      return next
    })

  const toggleAll = () =>
    setSelected(allSelected ? new Set() : new Set(rows.map((r) => r.studentId)))

  const mutation = useMutation({
    mutationFn: () =>
      bulkGrade(id, {
        studentIds: [...selected],
        status,
        // An empty box means "no numeric score", which is a real outcome here --
        // not zero, which would drag the student's average down.
        score: score.trim() === '' ? undefined : Number(score),
        comment: comment.trim() === '' ? undefined : comment.trim(),
      }),
    onSuccess: (r) => {
      setError(null)
      setResult(r.message || `Graded ${r.graded} of ${r.requested}.`)
      setSelected(new Set())
      queryClient.invalidateQueries({ queryKey: ['grading-grid', id] })
    },
    onError: (e: any) => {
      setResult(null)
      setError(e?.response?.data?.message || 'Could not save. Nothing was changed.')
    },
  })

  const scoreInvalid =
    score.trim() !== '' && (Number.isNaN(Number(score)) || Number(score) < 0 || Number(score) > 100)

  return (
    <div className="space-y-6 p-6">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>
          <ArrowLeft className="mr-2 h-4 w-4" />
          Back
        </Button>
        <div>
          <h1 className="text-2xl font-bold">Grade topic</h1>
          <p className="text-sm text-muted-foreground">
            Select students, choose an outcome, and save them together.
          </p>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Outcome to apply</CardTitle>
          <CardDescription>
            Applied to every selected student in one request.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-4">
          <div className="space-y-2">
            <Label htmlFor="status">Status</Label>
            <Select value={status} onValueChange={(v) => setStatus(v as ProgressStatus)}>
              <SelectTrigger id="status">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {STATUSES.map((s) => (
                  <SelectItem key={s.value} value={s.value}>{s.label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label htmlFor="score">Score (optional, 0–100)</Label>
            <Input
              id="score"
              inputMode="numeric"
              placeholder="Leave blank for no score"
              value={score}
              onChange={(e) => setScore(e.target.value)}
            />
            {scoreInvalid && (
              <p className="text-xs text-red-600">Score must be a number between 0 and 100.</p>
            )}
          </div>

          <div className="space-y-2 md:col-span-2">
            <Label htmlFor="comment">Comment (optional)</Label>
            <Input
              id="comment"
              placeholder="Feedback for the selected students"
              value={comment}
              onChange={(e) => setComment(e.target.value)}
            />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <div>
            <CardTitle>Students</CardTitle>
            <CardDescription>
              {isLoading ? 'Loading…' : `${rows.length} enrolled · ${selected.size} selected`}
            </CardDescription>
          </div>
          <Button
            onClick={() => mutation.mutate()}
            disabled={selected.size === 0 || scoreInvalid || mutation.isPending}
          >
            {mutation.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
            Save {selected.size > 0 ? `${selected.size} student${selected.size > 1 ? 's' : ''}` : ''}
          </Button>
        </CardHeader>
        <CardContent className="space-y-4">
          {result && (
            <div className="flex items-center gap-2 rounded-md bg-green-50 p-3 text-sm text-green-800">
              <CheckCircle2 className="h-4 w-4" />
              {result}
            </div>
          )}
          {error && (
            <div className="rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</div>
          )}

          {!isLoading && rows.length === 0 ? (
            <p className="py-8 text-center text-sm text-muted-foreground">
              No students have progress records for this topic yet. If students enrolled before this
              topic was added, run a backfill on the batch.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-10">
                      <Checkbox
                        checked={allSelected}
                        onCheckedChange={toggleAll}
                        aria-label="Select all students"
                      />
                    </TableHead>
                    <TableHead>Roll number</TableHead>
                    <TableHead>Name</TableHead>
                    <TableHead>Current status</TableHead>
                    <TableHead>Score</TableHead>
                    <TableHead>Last graded by</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rows.map((row) => (
                    <TableRow key={row.studentId}>
                      <TableCell>
                        <Checkbox
                          checked={selected.has(row.studentId)}
                          onCheckedChange={() => toggle(row.studentId)}
                          aria-label={`Select ${row.studentName}`}
                        />
                      </TableCell>
                      <TableCell className="font-mono text-xs">{row.rollNumber}</TableCell>
                      <TableCell className="font-medium">{row.studentName}</TableCell>
                      <TableCell>
                        <Badge className={STATUS_STYLE[row.status]} variant="secondary">
                          {STATUSES.find((s) => s.value === row.status)?.label ?? row.status}
                        </Badge>
                      </TableCell>
                      <TableCell>{row.score ?? '—'}</TableCell>
                      <TableCell className="text-sm text-muted-foreground">
                        {row.gradedByTrainerName ?? '—'}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
