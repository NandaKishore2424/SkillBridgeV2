/**
 * Batch Details Page
 * 
 * Comprehensive batch management page with tabs for:
 * - Overview: Basic info and statistics
 * - Trainers: Assign/unassign trainers
 * - Companies: Map/unmap companies
 * - Enrollments: Approve/reject student applications
 * - Syllabus: Manage syllabus topics
 */

import { useParams } from 'react-router-dom'
import SyllabusTab from '@/components/batch-management/SyllabusTab'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { AuthenticatedLayout } from '@/shared/components/layout'
import { PageWrapper } from '@/shared/components/layout'
import { RoleGuard } from '@/shared/components/auth'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Button,
  Badge,
  Alert,
  AlertDescription,
  Tabs,
  TabsList,
  TabsTrigger,
  TabsContent,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  Checkbox,
  Label,
} from '@/shared/components/ui'
import {
  getBatchDetails,
  assignTrainersToBatch,
  mapCompaniesToBatch,
  getBatchEnrollments,
  approveEnrollment,
  rejectEnrollment,
  type BatchDetails as BatchDetailsResponse,
} from '@/api/batch-details'
import { getAssignedTrainers as getBatchTrainers, getAssignedCompanies as getBatchCompanies } from '@/api/batch-details'
import { getTrainers, getCompanies } from '@/api/college-admin'
import {
  ArrowLeft,
  Loader2,
  AlertCircle,
  GraduationCap,
  Check,
  XCircle,
} from 'lucide-react'
import { Link } from 'react-router-dom'
import { useMemo } from 'react'
import { useSyncedState } from '@/shared/hooks/useSyncedState'
import { itemsOf } from '@/api/paging'
import { apiErrorMessage } from '@/lib/apiError'

const STATUS_COLORS: Record<string, 'default' | 'secondary' | 'outline'> = {
  UPCOMING: 'outline',
  OPEN: 'default',
  ACTIVE: 'default',
  COMPLETED: 'secondary',
  CANCELLED: 'secondary',
}

// Tab components
function OverviewTab({ batch }: { batch: BatchDetailsResponse }) {
  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Batch Information</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div>
            <Label className="text-sm font-medium text-muted-foreground">Name</Label>
            <p className="text-lg font-semibold">{batch.name}</p>
          </div>
          {batch.description && (
            <div>
              <Label className="text-sm font-medium text-muted-foreground">Description</Label>
              <p className="text-sm">{batch.description}</p>
            </div>
          )}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <Label className="text-sm font-medium text-muted-foreground">Status</Label>
              <div className="mt-1">
                <Badge variant={STATUS_COLORS[batch.status]}>{batch.status}</Badge>
              </div>
            </div>
            <div>
              <Label className="text-sm font-medium text-muted-foreground">Enrolled Students</Label>
              <p className="text-lg font-semibold">{batch.enrolledCount || 0}</p>
            </div>
          </div>
          {batch.startDate && batch.endDate && (
            <div className="grid grid-cols-2 gap-4">
              <div>
                <Label className="text-sm font-medium text-muted-foreground">Start Date</Label>
                <p className="text-sm">
                  {new Date(batch.startDate).toLocaleDateString()}
                </p>
              </div>
              <div>
                <Label className="text-sm font-medium text-muted-foreground">End Date</Label>
                <p className="text-sm">
                  {new Date(batch.endDate).toLocaleDateString()}
                </p>
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

function TrainersTab({ batchId }: { batchId: number }) {
  const queryClient = useQueryClient()
  const { showSuccess, showError } = useToastNotifications()

  // Two bugs lived in these three lines. `queryFn: getTrainers` hands React
  // Query's context object to getTrainers' `page` parameter, and the response is
  // a PagedResponse envelope, not an array -- so `allTrainers.map(...)` below
  // threw "map is not a function" and took the whole page down to a white
  // screen. Both are now explicit.
  const { data: trainersPage, isLoading } = useQuery({
    queryKey: ['admin', 'trainers'],
    queryFn: () => getTrainers(0, 200),
  })
  const allTrainers = trainersPage?.items ?? []

  // Fetch assigned trainers
  const { data: assignedTrainers } = useQuery({
    queryKey: ['admin', 'batches', batchId, 'trainers'],
    queryFn: () => getBatchTrainers(batchId, { size: 100 }),
    select: itemsOf,
  })

  const assignedTrainerIds = useMemo(
    () => assignedTrainers?.map((t) => t.id) ?? [],
    [assignedTrainers],
  )

  // Seeded from what is already assigned, and re-seeded when that changes --
  // during the render that notices, not in an effect afterwards, so the
  // checkboxes never show the previous assignment for a frame.
  const [selectedTrainers, setSelectedTrainers] = useSyncedState(
    assignedTrainerIds,
    assignedTrainers,
  )

  const assignMutation = useMutation({
    mutationFn: (trainerIds: number[]) => assignTrainersToBatch(batchId, trainerIds),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId] })
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches'] })
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId, 'trainers'] })
      showSuccess('Trainers assigned successfully!')
    },
    onError: (error: unknown) => {
      showError(apiErrorMessage(error, 'Failed to assign trainers'))
    },
  })

  // An unassign mutation was defined here with no button wired to it, calling
  // DELETE /admin/batches/{id}/trainers/{trainerId} -- an endpoint the backend
  // does not implement. Half a feature on both sides. Removed so the build is
  // green; restore it together with the endpoint and a button.

  const handleSave = () => {
    assignMutation.mutate(selectedTrainers)
  }

  const handleToggle = (trainerId: number) => {
    setSelectedTrainers((prev) =>
      prev.includes(trainerId) ? prev.filter((id) => id !== trainerId) : [...prev, trainerId]
    )
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Assign Trainers</CardTitle>
          <CardDescription>
            Select trainers to assign to this batch. Multiple trainers can be assigned.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-4">
            {/* Show assigned trainers at top */}
            {assignedTrainers && assignedTrainers.length > 0 && (
              <div className="mb-4 p-3 bg-muted/50 rounded-md">
                <p className="text-sm font-medium mb-2">Currently Assigned ({assignedTrainers.length}):</p>
                <div className="flex flex-wrap gap-2">
                  {assignedTrainers.map(trainer => (
                    <Badge key={trainer.id} variant="default">
                      {trainer.fullName}
                    </Badge>
                  ))}
                </div>
              </div>
            )}

            <div className="space-y-2">
              {allTrainers.map((trainer) => (
                <div
                  key={trainer.id}
                  className="flex items-center space-x-2 p-3 border rounded-md hover:bg-muted/50"
                >
                  <Checkbox
                    id={`trainer-${trainer.id}`}
                    checked={selectedTrainers.includes(trainer.id)}
                    onCheckedChange={() => handleToggle(trainer.id)}
                  />
                  <Label
                    htmlFor={`trainer-${trainer.id}`}
                    className="flex-1 cursor-pointer"
                  >
                    <div className="flex items-center justify-between">
                      <div>
                        <p className="font-medium">{trainer.fullName}</p>
                        <p className="text-sm text-muted-foreground">{trainer.email}</p>
                        {trainer.department && (
                          <p className="text-xs text-muted-foreground">
                            {trainer.department}
                            {trainer.specialization && ` • ${trainer.specialization}`}
                          </p>
                        )}
                      </div>
                      {!trainer.isActive && (
                        <Badge variant="secondary" className="ml-2">
                          Inactive
                        </Badge>
                      )}
                    </div>
                  </Label>
                </div>
              ))}
            </div>
            <Button
              onClick={handleSave}
              disabled={assignMutation.isPending}
              className="w-full"
            >
              {assignMutation.isPending ? (
                <>
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  Saving...
                </>
              ) : (
                'Save Assignments'
              )}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}

function CompaniesTab({ batchId }: { batchId: number }) {
  const queryClient = useQueryClient()
  const { showSuccess, showError } = useToastNotifications()

  const { data: companiesPage, isLoading } = useQuery({
    queryKey: ['admin', 'companies'],
    queryFn: () => getCompanies(0, 200),
  })
  const allCompanies = companiesPage?.items ?? []

  // Fetch assigned companies
  const { data: assignedCompanies } = useQuery({
    queryKey: ['admin', 'batches', batchId, 'companies'],
    queryFn: () => getBatchCompanies(batchId, { size: 100 }),
    select: itemsOf,
  })

  const assignedCompanyIds = useMemo(
    () => assignedCompanies?.map((c) => c.id) ?? [],
    [assignedCompanies],
  )

  // Same shape as the trainers tab above, for the same reason.
  const [selectedCompanies, setSelectedCompanies] = useSyncedState(
    assignedCompanyIds,
    assignedCompanies,
  )

  const mapMutation = useMutation({
    mutationFn: (companyIds: number[]) => mapCompaniesToBatch(batchId, companyIds),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId] })
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches'] })
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId, 'companies'] })
      showSuccess('Companies mapped successfully!')
    },
    onError: (error: unknown) => {
      showError(apiErrorMessage(error, 'Failed to map companies'))
    },
  })

  const handleSave = () => {
    mapMutation.mutate(selectedCompanies)
  }

  const handleToggle = (companyId: number) => {
    setSelectedCompanies((prev) =>
      prev.includes(companyId) ? prev.filter((id) => id !== companyId) : [...prev, companyId]
    )
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Map Companies</CardTitle>
          <CardDescription>
            Select companies to link with this batch. Students will see these companies when
            enrolled.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-4">
            {/* Show assigned companies at top */}
            {assignedCompanies && assignedCompanies.length > 0 && (
              <div className="mb-4 p-3 bg-muted/50 rounded-md">
                <p className="text-sm font-medium mb-2">Currently Assigned ({assignedCompanies.length}):</p>
                <div className="flex flex-wrap gap-2">
                  {assignedCompanies.map(company => (
                    <Badge key={company.id} variant="default">
                      {company.name}
                    </Badge>
                  ))}
                </div>
              </div>
            )}

            <div className="space-y-2">
              {allCompanies.map((company) => (
                <div
                  key={company.id}
                  className="flex items-center space-x-2 p-3 border rounded-md hover:bg-muted/50"
                >
                  <Checkbox
                    id={`company-${company.id}`}
                    checked={selectedCompanies.includes(company.id)}
                    onCheckedChange={() => handleToggle(company.id)}
                  />
                  <Label
                    htmlFor={`company-${company.id}`}
                    className="flex-1 cursor-pointer"
                  >
                    <div className="flex items-center justify-between">
                      <div>
                        <p className="font-medium">{company.name}</p>
                        {company.domain && (
                          <p className="text-sm text-muted-foreground">{company.domain}</p>
                        )}
                        <Badge variant="outline" className="mt-1">
                          {company.hiringType === 'FULL_TIME'
                            ? 'Full Time'
                            : company.hiringType === 'INTERNSHIP'
                              ? 'Internship'
                              : 'Both'}
                        </Badge>
                      </div>
                    </div>
                  </Label>
                </div>
              ))}
            </div>
            <Button
              onClick={handleSave}
              disabled={mapMutation.isPending}
              className="w-full"
            >
              {mapMutation.isPending ? (
                <>
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  Saving...
                </>
              ) : (
                'Save Mappings'
              )}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}

function EnrollmentsTab({ batchId }: { batchId: number }) {
  const queryClient = useQueryClient()
  const { showSuccess, showError } = useToastNotifications()

  const {
    data: enrollments,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['admin', 'batches', batchId, 'enrollments'],
    queryFn: () => getBatchEnrollments(batchId),
  })

  const approveMutation = useMutation({
    // These address the enrollment REQUEST, not the enrollment, and the request
    // already knows its batch -- see api/college-admin.ts.
    mutationFn: (requestId: number) => approveEnrollment(requestId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId, 'enrollments'] })
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId] })
      showSuccess('Enrollment approved successfully!')
    },
    onError: (error: unknown) => {
      showError(apiErrorMessage(error, 'Failed to approve enrollment'))
    },
  })

  const rejectMutation = useMutation({
    mutationFn: (requestId: number) => rejectEnrollment(requestId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId, 'enrollments'] })
      showSuccess('Enrollment rejected')
    },
    onError: (error: unknown) => {
      showError(apiErrorMessage(error, 'Failed to reject enrollment'))
    },
  })

  const handleApprove = (enrollmentId: number) => {
    if (confirm('Approve this enrollment?')) {
      approveMutation.mutate(enrollmentId)
    }
  }

  const handleReject = (enrollmentId: number) => {
    if (confirm('Reject this enrollment?')) {
      rejectMutation.mutate(enrollmentId)
    }
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  if (error) {
    return (
      <Alert variant="destructive">
        <AlertCircle className="h-4 w-4" />
        <AlertDescription>Failed to load enrollments</AlertDescription>
      </Alert>
    )
  }

  const pendingEnrollments = enrollments?.filter((e) => e.status === 'PENDING') || []
  const approvedEnrollments = enrollments?.filter((e) => e.status === 'APPROVED') || []
  const rejectedEnrollments = enrollments?.filter((e) => e.status === 'REJECTED') || []

  return (
    <div className="space-y-4">
      {/* Pending Enrollments */}
      {pendingEnrollments.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Pending Enrollments ({pendingEnrollments.length})</CardTitle>
            <CardDescription>Review and approve or reject applications</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Student</TableHead>
                    <TableHead>Roll Number</TableHead>
                    <TableHead>Applied At</TableHead>
                    <TableHead className="text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {pendingEnrollments.map((enrollment) => (
                    <TableRow key={enrollment.id}>
                      <TableCell className="font-medium">
                        {/* StudentWithDetails is flat: the DTO exposes `email`, not a nested `user`. */}
                        {enrollment.student?.email || 'N/A'}
                      </TableCell>
                      <TableCell>{enrollment.student?.rollNumber || 'N/A'}</TableCell>
                      <TableCell>
                        {new Date(enrollment.appliedAt).toLocaleDateString()}
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-2">
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => handleApprove(enrollment.id)}
                            disabled={approveMutation.isPending}
                          >
                            <Check className="mr-1 h-3 w-3" />
                            Approve
                          </Button>
                          <Button
                            size="sm"
                            variant="destructive"
                            onClick={() => handleReject(enrollment.id)}
                            disabled={rejectMutation.isPending}
                          >
                            <XCircle className="mr-1 h-3 w-3" />
                            Reject
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Approved Enrollments */}
      {approvedEnrollments.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Approved Enrollments ({approvedEnrollments.length})</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Student</TableHead>
                    <TableHead>Roll Number</TableHead>
                    <TableHead>Applied At</TableHead>
                    <TableHead>Status</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {approvedEnrollments.map((enrollment) => (
                    <TableRow key={enrollment.id}>
                      <TableCell className="font-medium">
                        {/* StudentWithDetails is flat: the DTO exposes `email`, not a nested `user`. */}
                        {enrollment.student?.email || 'N/A'}
                      </TableCell>
                      <TableCell>{enrollment.student?.rollNumber || 'N/A'}</TableCell>
                      <TableCell>
                        {new Date(enrollment.appliedAt).toLocaleDateString()}
                      </TableCell>
                      <TableCell>
                        <Badge variant="default">Approved</Badge>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Rejected Enrollments */}
      {rejectedEnrollments.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Rejected Enrollments ({rejectedEnrollments.length})</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Student</TableHead>
                    <TableHead>Roll Number</TableHead>
                    <TableHead>Applied At</TableHead>
                    <TableHead>Status</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rejectedEnrollments.map((enrollment) => (
                    <TableRow key={enrollment.id}>
                      <TableCell className="font-medium">
                        {/* StudentWithDetails is flat: the DTO exposes `email`, not a nested `user`. */}
                        {enrollment.student?.email || 'N/A'}
                      </TableCell>
                      <TableCell>{enrollment.student?.rollNumber || 'N/A'}</TableCell>
                      <TableCell>
                        {new Date(enrollment.appliedAt).toLocaleDateString()}
                      </TableCell>
                      <TableCell>
                        <Badge variant="secondary">Rejected</Badge>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          </CardContent>
        </Card>
      )}

      {enrollments?.length === 0 && (
        <Card>
          <CardContent className="py-12 text-center">
            <GraduationCap className="mx-auto h-12 w-12 text-muted-foreground mb-4" />
            <p className="text-muted-foreground">No enrollments yet</p>
          </CardContent>
        </Card>
      )}
    </div>
  )
}


export function BatchDetails() {
  const { id } = useParams<{ id: string }>()
  // navigate was unused: this component never redirects.
  const batchId = parseInt(id || '0')

  const {
    data: batch,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['admin', 'batches', batchId],
    queryFn: () => getBatchDetails(batchId),
    enabled: !!batchId,
  })

  if (isLoading) {
    return (
      <RoleGuard allowedRoles={['COLLEGE_ADMIN']}>
        <AuthenticatedLayout>
          <PageWrapper>
            <div className="flex items-center justify-center py-12">
              <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
            </div>
          </PageWrapper>
        </AuthenticatedLayout>
      </RoleGuard>
    )
  }

  if (error || !batch) {
    return (
      <RoleGuard allowedRoles={['COLLEGE_ADMIN']}>
        <AuthenticatedLayout>
          <PageWrapper>
            <Alert variant="destructive">
              <AlertCircle className="h-4 w-4" />
              <AlertDescription>
                Failed to load batch details. Please try again.
              </AlertDescription>
            </Alert>
          </PageWrapper>
        </AuthenticatedLayout>
      </RoleGuard>
    )
  }

  return (
    <RoleGuard allowedRoles={['COLLEGE_ADMIN']}>
      <AuthenticatedLayout>
        <PageWrapper>
          <div className="space-y-6">
            {/* Header */}
            <div className="flex items-center gap-4">
              <Button variant="ghost" size="icon" asChild>
                <Link to="/admin/batches">
                  <ArrowLeft className="h-4 w-4" />
                </Link>
              </Button>
              <div className="flex-1">
                <h1 className="text-3xl font-bold tracking-tight">{batch.name}</h1>
                <p className="text-muted-foreground">Manage batch details and settings</p>
              </div>
            </div>

            {/* Tabs */}
            <Tabs defaultValue="overview" className="space-y-4">
              <TabsList>
                <TabsTrigger value="overview">Overview</TabsTrigger>
                <TabsTrigger value="trainers">
                  {/*
                    batch.trainers / batch.companies / batch.enrolledCount are not
                    fields BatchDTO has ever sent, so every one of these labels read
                    (0) regardless of the data -- while the tab body below correctly
                    showed "Currently Assigned (1)". The DTO sends counts, not
                    collections.
                  */}
                  Trainers ({batch.trainerCount ?? 0})
                </TabsTrigger>
                <TabsTrigger value="companies">
                  Companies ({batch.companyCount ?? 0})
                </TabsTrigger>
                <TabsTrigger value="enrollments">
                  Enrollments ({batch.studentCount ?? 0})
                </TabsTrigger>
                <TabsTrigger value="syllabus">Syllabus</TabsTrigger>
              </TabsList>

              <TabsContent value="overview">
                <OverviewTab batch={batch} />
              </TabsContent>

              {/*
                `trainers` / `assignedTrainerIds` and their company equivalents
                used to be passed here and were never read: both tabs fetch
                their own lists. They survived because the components were typed
                `({ batchId }: any)`, which accepts any props at all and reports
                nothing.
              */}
              <TabsContent value="trainers">
                <TrainersTab batchId={batchId} />
              </TabsContent>

              <TabsContent value="companies">
                <CompaniesTab batchId={batchId} />
              </TabsContent>

              <TabsContent value="enrollments">
                <EnrollmentsTab batchId={batchId} />
              </TabsContent>

              <TabsContent value="syllabus">
                {/*
                  Was a local SyllabusTab built on the legacy flat-syllabus API --
                  five endpoints the backend never implemented, so every action in
                  it failed. This is the same module-tree component the trainer
                  batch page uses, which talks to the curriculum endpoints that
                  actually exist. College admins can now reach it because the
                  syllabus controller no longer guards on a nonexistent ADMIN role.
                */}
                <SyllabusTab batchId={batchId} />
              </TabsContent>
            </Tabs>
          </div>
        </PageWrapper>
      </AuthenticatedLayout>
    </RoleGuard>
  )
}

