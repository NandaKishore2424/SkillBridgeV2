import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
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
    Input,
    Table,
    TableBody,
    TableCell,
    TableHead,
    TableHeader,
    TableRow,
} from '@/shared/components/ui'
import {
    downloadTemplate,
    getUpload,
    getUploadHistory,
    getUploadRows,
    resendInvitation,
    uploadCsv,
    type BulkUploadResponse,
    type ImportKind,
} from '@/api/bulk-upload'
import { itemsOf } from '@/api/paging'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import { apiErrorMessage } from '@/lib/apiError'
import { AlertCircle, ArrowLeft, CheckCircle, FileDown, History, Loader2, Mail, Upload } from 'lucide-react'
import { TableSkeleton } from '@/shared/components/ui/loading-skeleton'

interface KindCopy {
    title: string
    noun: string
    backTo: string
    required: string
    optional: string
}

const COPY: Record<ImportKind, KindCopy> = {
    students: {
        title: 'Bulk Upload Students',
        noun: 'student',
        backTo: '/admin/students',
        required: 'Full Name, Email, Roll Number',
        optional: 'Degree, Branch, Year (1 to 8)',
    },
    trainers: {
        title: 'Bulk Upload Trainers',
        noun: 'trainer',
        backTo: '/admin/trainers',
        required: 'Full Name, Email',
        optional: 'Department, Specialization',
    },
}

/** The error code the API put in the body, e.g. EMAIL_NOT_SENT. */
function apiErrorCode(error: unknown): string | undefined {
    return (error as { response?: { data?: { error?: string } } })?.response?.data?.error
}

export function BulkUploadScreen({ kind }: { kind: ImportKind }) {
    const copy = COPY[kind]
    const [file, setFile] = useState<File | null>(null)
    const [uploadResult, setUploadResult] = useState<BulkUploadResponse | null>(null)
    const [uploadError, setUploadError] = useState<string | null>(null)
    const [selectedId, setSelectedId] = useState<number | null>(null)
    const queryClient = useQueryClient()
    const { showSuccess, showError } = useToastNotifications()
    const historyKey = ['admin', kind, 'upload-history']

    const { data: history, isLoading: isHistoryLoading } = useQuery({
        queryKey: historyKey,
        queryFn: () => getUploadHistory(kind, { size: 100 }),
        // `select` shapes what the hook returns; the cache still holds the raw
        // PagedResponse, and refetchInterval reads the cache. So this predicate
        // goes through `.items` while the component below sees a plain array.
        select: itemsOf,
        refetchInterval: (query) =>
            query.state.data?.items.some((record) => record.status === 'PROCESSING') ? 5000 : false,
    })

    const uploadMutation = useMutation({
        mutationFn: (f: File) => uploadCsv(kind, f),
        onSuccess: (data) => {
            setUploadResult(data)
            setUploadError(null)
            setSelectedId(data.uploadId)
            showSuccess(data.alreadyUploaded
                ? 'This file was already uploaded; showing that upload.'
                : `${data.rows} rows queued for import.`)
            queryClient.invalidateQueries({ queryKey: historyKey })
            setFile(null)
            const fileInput = document.getElementById('file-upload') as HTMLInputElement | null
            if (fileInput) fileInput.value = ''
        },
        onError: (error: unknown) => {
            // 400 and 413 name what is wrong with the file; keep it on screen.
            setUploadResult(null)
            setUploadError(apiErrorMessage(error, 'Upload failed'))
        },
    })

    const handleDownloadTemplate = async () => {
        try {
            const blob = await downloadTemplate(kind)
            const url = window.URL.createObjectURL(blob)
            const a = document.createElement('a')
            a.href = url
            a.download = `${copy.noun}_template.csv`
            document.body.appendChild(a)
            a.click()
            window.URL.revokeObjectURL(url)
            document.body.removeChild(a)
        } catch {
            showError('Failed to download template')
        }
    }

    return (
        <RoleGuard allowedRoles={['COLLEGE_ADMIN']}>
            <AuthenticatedLayout>
                <PageWrapper>
                    <div className="space-y-6">
                        <div className="flex items-center gap-4">
                            <Button variant="outline" size="icon" asChild>
                                <Link to={copy.backTo}>
                                    <ArrowLeft className="h-4 w-4" />
                                </Link>
                            </Button>
                            <div>
                                <h1 className="text-3xl font-bold tracking-tight">{copy.title}</h1>
                                <p className="text-muted-foreground">
                                    Upload a CSV file to add many {copy.noun}s at once. Each gets an invitation email.
                                </p>
                            </div>
                        </div>

                        <div className="grid gap-6 md:grid-cols-2">
                            <Card>
                                <CardHeader>
                                    <CardTitle>Upload CSV</CardTitle>
                                    <CardDescription>The file is checked as a whole before anything is imported.</CardDescription>
                                </CardHeader>
                                <CardContent className="space-y-4">
                                    <Input
                                        id="file-upload"
                                        type="file"
                                        accept=".csv"
                                        onChange={(e) => {
                                            setFile(e.target.files?.[0] ?? null)
                                            setUploadResult(null)
                                            setUploadError(null)
                                        }}
                                        disabled={uploadMutation.isPending}
                                    />
                                    <div className="flex gap-2">
                                        <Button
                                            onClick={() => file && uploadMutation.mutate(file)}
                                            disabled={!file || uploadMutation.isPending}
                                            className="w-full sm:w-auto"
                                        >
                                            {uploadMutation.isPending
                                                ? <><Loader2 className="mr-2 h-4 w-4 animate-spin" />Checking...</>
                                                : <><Upload className="mr-2 h-4 w-4" />Upload File</>}
                                        </Button>
                                        <Button variant="outline" onClick={handleDownloadTemplate} className="w-full sm:w-auto">
                                            <FileDown className="mr-2 h-4 w-4" />
                                            Download Template
                                        </Button>
                                    </div>
                                </CardContent>
                            </Card>

                            <Card>
                                <CardHeader>
                                    <CardTitle>Instructions</CardTitle>
                                </CardHeader>
                                <CardContent>
                                    <ul className="list-disc pl-4 space-y-2 text-sm text-muted-foreground">
                                        <li>Required columns: {copy.required}. Optional: {copy.optional}.</li>
                                        <li>Save as CSV UTF-8. At most 1 MB and 2,000 rows.</li>
                                        <li>A bad row is reported with its reason; the other rows still import.</li>
                                        <li>Uploading the same file again does not import it twice.</li>
                                    </ul>
                                </CardContent>
                            </Card>
                        </div>

                        {uploadError && (
                            <Alert variant="destructive">
                                <AlertCircle className="h-4 w-4" />
                                <AlertDescription>
                                    <div className="font-medium">This file was not imported</div>
                                    <div className="text-sm">{uploadError}</div>
                                </AlertDescription>
                            </Alert>
                        )}

                        {uploadResult?.alreadyUploaded && (
                            <Alert>
                                <AlertCircle className="h-4 w-4" />
                                <AlertDescription>
                                    This exact file was uploaded before, so it was not imported again. Its results are below.
                                </AlertDescription>
                            </Alert>
                        )}

                        {selectedId !== null && <UploadDetails kind={kind} uploadId={selectedId} />}

                        <Card>
                            <CardHeader>
                                <div className="flex items-center gap-2">
                                    <History className="h-5 w-5 text-muted-foreground" />
                                    <CardTitle>Upload History</CardTitle>
                                </div>
                            </CardHeader>
                            <CardContent>
                                {isHistoryLoading ? (
                                    <TableSkeleton rows={3} columns={5} />
                                ) : history && history.length > 0 ? (
                                    <Table>
                                        <TableHeader>
                                            <TableRow>
                                                <TableHead>Date</TableHead>
                                                <TableHead>File Name</TableHead>
                                                <TableHead>Status</TableHead>
                                                <TableHead>Rows</TableHead>
                                                <TableHead />
                                            </TableRow>
                                        </TableHeader>
                                        <TableBody>
                                            {history.map((record) => (
                                                <TableRow key={record.id} data-state={record.id === selectedId ? 'selected' : undefined}>
                                                    <TableCell>{new Date(record.createdAt).toLocaleString()}</TableCell>
                                                    <TableCell>{record.fileName}</TableCell>
                                                    <TableCell><StatusBadge status={record.status} /></TableCell>
                                                    <TableCell>
                                                        <div className="text-xs">
                                                            <span className="text-green-600 font-medium">{record.successfulRows} imported</span>
                                                            <span className="text-gray-300 mx-1">|</span>
                                                            <span className="text-red-600 font-medium">{record.failedRows} failed</span>
                                                        </div>
                                                    </TableCell>
                                                    <TableCell className="text-right">
                                                        <Button variant="ghost" size="sm" onClick={() => setSelectedId(record.id)}>
                                                            Details
                                                        </Button>
                                                    </TableCell>
                                                </TableRow>
                                            ))}
                                        </TableBody>
                                    </Table>
                                ) : (
                                    <div className="text-center py-8 text-muted-foreground">No upload history found.</div>
                                )}
                            </CardContent>
                        </Card>
                    </div>
                </PageWrapper>
            </AuthenticatedLayout>
        </RoleGuard>
    )
}

function StatusBadge({ status }: { status: string }) {
    const variant = status === 'FAILED' ? 'destructive' : status === 'COMPLETED' ? 'outline' : 'secondary'
    return <Badge variant={variant}>{status}</Badge>
}

/** One upload's outcome, and every row that needs the admin's attention. */
export function UploadDetails({ kind, uploadId }: { kind: ImportKind; uploadId: number }) {
    const { showSuccess, showError } = useToastNotifications()
    const [resent, setResent] = useState<Set<number>>(new Set())

    const { data: upload } = useQuery({
        queryKey: ['admin', 'bulk-upload', uploadId],
        queryFn: () => getUpload(uploadId),
        refetchInterval: (query) => (query.state.data?.status === 'PROCESSING' ? 2000 : false),
    })
    const done = upload !== undefined && upload.status !== 'PROCESSING'
    const { data: rows, isLoading: rowsLoading } = useQuery({
        queryKey: ['admin', 'bulk-upload', uploadId, 'rows', upload?.completedAt],
        queryFn: () => getUploadRows(uploadId, ['FAILED', 'EMAIL_FAILED'], { size: 200 }),
        enabled: done,
        select: itemsOf,
    })

    const resend = useMutation({
        mutationFn: (userId: number) => resendInvitation(kind, userId),
        onSuccess: (_, userId) => {
            setResent((prev) => new Set(prev).add(userId))
            showSuccess('Invitation sent.')
        },
        onError: (error: unknown) => {
            showError(apiErrorCode(error) === 'EMAIL_NOT_SENT'
                ? 'The invitation still could not be sent. Check the mail settings and try again.'
                : apiErrorMessage(error, 'Could not resend the invitation'))
        },
    })

    if (!upload) {
        return null
    }
    const clean = done && upload.failedRows === 0 && upload.emailFailedRows === 0 && upload.status === 'COMPLETED'

    return (
        <Card>
            <CardHeader>
                <div className="flex items-center justify-between gap-2">
                    <CardTitle className="text-lg">{upload.fileName}</CardTitle>
                    <StatusBadge status={upload.status} />
                </div>
                <CardDescription>
                    {upload.status === 'PROCESSING'
                        ? `Importing... ${upload.totalRows} rows so far`
                        : `${upload.totalRows} rows: ${upload.successfulRows} imported, ${upload.failedRows} failed`
                          + (upload.emailFailedRows > 0 ? `, ${upload.emailFailedRows} not emailed` : '')}
                </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
                {upload.errorReport && (
                    <Alert variant="destructive">
                        <AlertCircle className="h-4 w-4" />
                        <AlertDescription>{upload.errorReport}</AlertDescription>
                    </Alert>
                )}
                {clean && (
                    <div className="flex items-center gap-2 text-sm text-green-700">
                        <CheckCircle className="h-4 w-4" /> Every row was imported and invited.
                    </div>
                )}
                {done && rowsLoading && <TableSkeleton rows={3} columns={4} />}
                {rows && rows.length > 0 && (
                    <Table>
                        <TableHeader>
                            <TableRow>
                                <TableHead>Row</TableHead>
                                <TableHead>Name</TableHead>
                                <TableHead>Email</TableHead>
                                <TableHead>Problem</TableHead>
                                <TableHead />
                            </TableRow>
                        </TableHeader>
                        <TableBody>
                            {rows.map((row) => (
                                <TableRow key={row.rowNumber}>
                                    <TableCell className="font-mono">{row.rowNumber}</TableCell>
                                    <TableCell>{row.values['Full Name'] || '-'}</TableCell>
                                    <TableCell>{row.values['Email'] || '-'}</TableCell>
                                    <TableCell className="text-sm text-red-600">{row.message}</TableCell>
                                    <TableCell className="text-right">
                                        {row.status === 'EMAIL_FAILED' && row.userId !== null && (
                                            resent.has(row.userId) ? (
                                                <span className="text-xs text-green-700">Sent</span>
                                            ) : (
                                                <Button
                                                    variant="outline"
                                                    size="sm"
                                                    disabled={resend.isPending}
                                                    onClick={() => resend.mutate(row.userId as number)}
                                                >
                                                    <Mail className="mr-1 h-3 w-3" /> Resend invitation
                                                </Button>
                                            )
                                        )}
                                    </TableCell>
                                </TableRow>
                            ))}
                        </TableBody>
                    </Table>
                )}
            </CardContent>
        </Card>
    )
}
