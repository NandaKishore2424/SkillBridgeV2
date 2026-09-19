import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Badge, Button, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/components/ui'
import { getMySkillGap, getStudentSkillGap, refreshMySkillGap, type SkillGapReport } from '@/api/skill-gap'
import { apiErrorMessage } from '@/lib/apiError'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import { Loader2, RefreshCw, Sparkles } from 'lucide-react'

/** How long to wait for the AI service after asking for a fresh analysis. */
const REFRESH_WAIT_MS = 60_000

/** The report itself, for whoever may see it. */
export function SkillGapReportView({ report }: { report: SkillGapReport | null }) {
    if (report === null) {
        return <p className="text-sm text-muted-foreground">No analysis yet. It runs when skills are added or changed.</p>
    }
    if (report.status === 'SKIPPED') {
        return <p className="text-sm text-muted-foreground">Add skills to the profile to get an analysis.</p>
    }
    return (
        <div className="space-y-4">
            <div>
                <div className="text-sm font-medium mb-2">Skills to learn next</div>
                {report.missingSkills.length > 0 ? (
                    <div className="flex flex-wrap gap-2">
                        {report.missingSkills.map((skill) => <Badge key={skill} variant="secondary">{skill}</Badge>)}
                    </div>
                ) : (
                    <p className="text-sm text-muted-foreground">
                        The closest jobs list no skills this profile is missing.
                    </p>
                )}
            </div>
            <div>
                <div className="text-sm font-medium mb-2">Closest jobs</div>
                {report.matchedJobs.length > 0 ? (
                    <ul className="space-y-2">
                        {report.matchedJobs.map((job, i) => (
                            <li key={i} className="flex items-start justify-between gap-4 text-sm">
                                <div>
                                    <div className="font-medium">{job.title}</div>
                                    {job.company && <div className="text-muted-foreground">{job.company}</div>}
                                    {job.missingSkills.length > 0 && (
                                        <div className="text-xs text-muted-foreground">Missing: {job.missingSkills.join(', ')}</div>
                                    )}
                                </div>
                                <span className="font-mono text-muted-foreground" title="Cosine similarity of the profile to the job">
                                    {Math.round(job.similarity * 100)}%
                                </span>
                            </li>
                        ))}
                    </ul>
                ) : (
                    <p className="text-sm text-muted-foreground">No job was similar enough to this profile.</p>
                )}
            </div>
            <p className="text-xs text-muted-foreground">
                Analysed {new Date(report.analyzedAt).toLocaleString()} from: {report.studentSkills.join(', ')}
            </p>
        </div>
    )
}

/** The signed-in student's report, with "Analyse again". */
export function MySkillGapCard() {
    const queryClient = useQueryClient()
    const { showError } = useToastNotifications()
    // Set when a refresh is asked for: the analyzedAt we wait to see change.
    // Cleared by a timer if the AI service never answers.
    const [waitingFor, setWaitingFor] = useState<{ previous: string | null } | null>(null)
    const stillWaiting = (r: SkillGapReport | null | undefined) =>
        waitingFor !== null && (r?.analyzedAt ?? null) === waitingFor.previous

    const { data: report, isLoading } = useQuery({
        queryKey: ['student', 'skill-gap'],
        queryFn: getMySkillGap,
        refetchInterval: (query) => (stillWaiting(query.state.data) ? 2000 : false),
    })

    const refresh = useMutation({
        mutationFn: refreshMySkillGap,
        onSuccess: () => {
            const marker = { previous: report?.analyzedAt ?? null }
            setWaitingFor(marker)
            setTimeout(() => setWaitingFor((current) => (current === marker ? null : current)), REFRESH_WAIT_MS)
            queryClient.invalidateQueries({ queryKey: ['student', 'skill-gap'] })
        },
        onError: (error: unknown) => showError(apiErrorMessage(error, 'Could not start an analysis')),
    })

    const busy = refresh.isPending || stillWaiting(report)
    return (
        <Card>
            <CardHeader className="flex flex-row items-start justify-between gap-4 space-y-0">
                <div>
                    <CardTitle className="flex items-center gap-2">
                        <Sparkles className="h-4 w-4" /> Skill gap
                    </CardTitle>
                    <CardDescription>Your skills compared with the industry jobs most like your profile.</CardDescription>
                </div>
                <Button variant="outline" size="sm" disabled={busy} onClick={() => refresh.mutate()}>
                    {busy ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <RefreshCw className="mr-2 h-4 w-4" />}
                    {busy ? 'Analysing...' : 'Analyse again'}
                </Button>
            </CardHeader>
            <CardContent>
                {isLoading ? <Loader2 className="h-4 w-4 animate-spin" /> : <SkillGapReportView report={report ?? null} />}
            </CardContent>
        </Card>
    )
}

/** A student's report as their college admin sees it. */
export function StudentSkillGap({ studentId }: { studentId: number }) {
    const { data: report, isLoading, error } = useQuery({
        queryKey: ['admin', 'students', studentId, 'skill-gap'],
        queryFn: () => getStudentSkillGap(studentId),
    })
    if (isLoading) return <Loader2 className="h-4 w-4 animate-spin" />
    if (error) return <p className="text-sm text-red-600">{apiErrorMessage(error, 'Could not load the report')}</p>
    return <SkillGapReportView report={report ?? null} />
}
