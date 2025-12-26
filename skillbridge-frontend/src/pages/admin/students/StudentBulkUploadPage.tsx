import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AuthenticatedLayout, PageWrapper } from '@/shared/components/layout'
import { RoleGuard } from '@/shared/components/auth'
import {
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
    Alert,
    AlertDescription,
    Badge,
} from '@/shared/components/ui'
import {
    uploadStudents,
    downloadStudentTemplate,
    getStudentUploadHistory,
    type BulkUploadResponse
} from '@/api/bulk-upload'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import {
    Loader2,
    Upload,
    FileDown,
    ArrowLeft,
    CheckCircle,
    AlertCircle,
    History,
} from 'lucide-react'
import { TableSkeleton } from '@/shared/components/ui/loading-skeleton'

export function StudentBulkUploadPage() {
    const [file, setFile] = useState<File | null>(null)
    const [uploadResult, setUploadResult] = useState<BulkUploadResponse | null>(null)
    const queryClient = useQueryClient()
    const { showSuccess, showError } = useToastNotifications()

    // History Query
    const { data: history, isLoading: isHistoryLoading } = useQuery({
        queryKey: ['admin', 'students', 'upload-history'],
        queryFn: getStudentUploadHistory,
    })

    // Upload Mutation
    const uploadMutation = useMutation({
        mutationFn: uploadStudents,
        onSuccess: (data) => {
            setUploadResult(data)
            showSuccess(`Processed ${data.totalRows} records`)
            queryClient.invalidateQueries({ queryKey: ['admin', 'students', 'upload-history'] })
            setFile(null)
            // Reset file input
            const fileInput = document.getElementById('file-upload') as HTMLInputElement
            if (fileInput) fileInput.value = ''
        },
        onError: (error: any) => {
            showError(error?.response?.data?.message || 'Upload failed')
        },
    })

    const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        if (e.target.files && e.target.files[0]) {
            setFile(e.target.files[0])
            setUploadResult(null)
        }
    }

    const handleUpload = () => {
        if (!file) return
        uploadMutation.mutate(file)
    }

    const handleDownloadTemplate = async () => {
        try {
            const blob = await downloadStudentTemplate()
            const url = window.URL.createObjectURL(blob)
            const a = document.createElement('a')
            a.href = url
            a.download = 'student_template.csv'
            document.body.appendChild(a)
            a.click()
            window.URL.revokeObjectURL(url)
            document.body.removeChild(a)
        } catch (error) {
            showError('Failed to download template')
        }
    }

    return (
        <RoleGuard allowedRoles={['COLLEGE_ADMIN']}>
            <AuthenticatedLayout>
                <PageWrapper>
                    <div className="space-y-6">
                        {/* Header */}
                        <div className="flex items-center gap-4">
                            <Button variant="outline" size="icon" asChild>
                                <Link to="/admin/students">
                                    <ArrowLeft className="h-4 w-4" />
                                </Link>
                            </Button>
                            <div>
                                <h1 className="text-3xl font-bold tracking-tight">Bulk Upload Students</h1>
                                <p className="text-muted-foreground">
                                    Upload CSV file to add multiple students at once
                                </p>
                            </div>
                        </div>

                        {/* Actions Card */}
                        <div className="grid gap-6 md:grid-cols-2">
                            <Card>
                                <CardHeader>
                                    <CardTitle>Upload CSV</CardTitle>
                                    <CardDescription>
                                        Select a CSV file containing student details.
                                    </CardDescription>
                                </CardHeader>
                                <CardContent className="space-y-4">
                                    <div className="grid w-full items-center gap-1.5">
                                        <Input
                                            id="file-upload"
                                            type="file"
                                            accept=".csv"
                                            onChange={handleFileChange}
                                            disabled={uploadMutation.isPending}
                                        />
                                    </div>

                                    <div className="flex gap-2">
                                        <Button
                                            onClick={handleUpload}
                                            disabled={!file || uploadMutation.isPending}
                                            className="w-full sm:w-auto"
                                        >
                                            {uploadMutation.isPending ? (
                                                <>
                                                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                                                    Processing...
                                                </>
                                            ) : (
                                                <>
                                                    <Upload className="mr-2 h-4 w-4" />
                                                    Upload File
                                                </>
                                            )}
                                        </Button>
                                        <Button
                                            variant="outline"
                                            onClick={handleDownloadTemplate}
                                            className="w-full sm:w-auto"
                                        >
                                            <FileDown className="mr-2 h-4 w-4" />
                                            Download Template
                                        </Button>
                                    </div>
                                </CardContent>
                            </Card>

                            {/* Instructions Card */}
                            <Card>
                                <CardHeader>
                                    <CardTitle>Instructions</CardTitle>
                                </CardHeader>
                                <CardContent>
                                    <ul className="list-disc pl-4 space-y-2 text-sm text-muted-foreground">
                                        <li>Download the template CSV file first.</li>
                                        <li>Fill in the details: Full Name, Email, Roll Number are mandatory.</li>
                                        <li>Ensure emails and roll numbers are unique.</li>
                                        <li>Do not change the header row in the CSV.</li>
                                        <li>Max file size: 5MB.</li>
                                    </ul>
                                </CardContent>
                            </Card>
                        </div>

                        {/* Results Alert */}
                        {uploadResult && (
                            <Alert variant={uploadResult.failedRows > 0 ? "destructive" : "default"} className={uploadResult.failedRows === 0 ? "border-green-500 text-green-700 bg-green-50" : ""}>
                                {uploadResult.failedRows === 0 ? <CheckCircle className="h-4 w-4" /> : <AlertCircle className="h-4 w-4" />}
                                <AlertDescription>
                                    <div className="font-medium mb-2">Upload Completed</div>
                                    <div className="grid grid-cols-3 gap-4 text-sm">
                                        <div>Total: {uploadResult.totalRows}</div>
                                        <div className="text-green-600">Success: {uploadResult.successfulRows}</div>
                                        <div className="text-red-600">Failed: {uploadResult.failedRows}</div>
                                    </div>
                                    {uploadResult.errors.length > 0 && (
                                        <div className="mt-4 max-h-40 overflow-y-auto bg-white/50 p-2 rounded text-xs font-mono">
                                            {uploadResult.errors.map((err, i) => (
                                                <div key={i} className="mb-1">
                                                    Row {err.rowNumber}: {err.errorMessage} ({err.rowData['email'] || 'Unknown'})
                                                </div>
                                            ))}
                                        </div>
                                    )}
                                </AlertDescription>
                            </Alert>
                        )}

                        {/* Upload History */}
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
                                                <TableHead>Stats</TableHead>
                                                <TableHead>Report</TableHead>
                                            </TableRow>
                                        </TableHeader>
                                        <TableBody>
                                            {history.map((record) => (
                                                <TableRow key={record.id}>
                                                    <TableCell>
                                                        {new Date(record.createdAt).toLocaleString()}
                                                    </TableCell>
                                                    <TableCell>{record.fileName}</TableCell>
                                                    <TableCell>
                                                        <Badge variant={record.status === 'COMPLETED' ? 'outline' : 'secondary'}>
                                                            {record.status}
                                                        </Badge>
                                                    </TableCell>
                                                    <TableCell>
                                                        <div className="text-xs">
                                                            <span className="text-green-600 font-medium">{record.successfulRows} OK</span>
                                                            <span className="text-gray-300 mx-1">|</span>
                                                            <span className="text-red-600 font-medium">{record.failedRows} Failed</span>
                                                        </div>
                                                    </TableCell>
                                                    <TableCell>
                                                        {record.errorReport && (
                                                            <span className="text-xs text-red-500 truncate max-w-[200px] block" title={record.errorReport}>
                                                                {record.errorReport}
                                                            </span>
                                                        )}
                                                    </TableCell>
                                                </TableRow>
                                            ))}
                                        </TableBody>
                                    </Table>
                                ) : (
                                    <div className="text-center py-8 text-muted-foreground">
                                        No upload history found.
                                    </div>
                                )}
                            </CardContent>
                        </Card>
                    </div>
                </PageWrapper>
            </AuthenticatedLayout>
        </RoleGuard>
    )
}
