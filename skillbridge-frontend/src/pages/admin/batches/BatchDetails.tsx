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

import { useParams, useNavigate } from 'react-router-dom'
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
  Input,
  Label,
  Textarea,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/shared/components/ui'
import {
  getBatchDetails,
  assignTrainersToBatch,
  unassignTrainerFromBatch,
  mapCompaniesToBatch,
  unmapCompanyFromBatch,
  getBatchEnrollments,
  approveEnrollment,
  rejectEnrollment,
  createSyllabus,
  addSyllabusTopic,
  updateSyllabusTopic,
  deleteSyllabusTopic,
} from '@/api/batch-details'
import { getAssignedTrainers as getBatchTrainers, getAssignedCompanies as getBatchCompanies } from '@/api/batch-details'
import { getTrainers, getCompanies } from '@/api/college-admin'
import {
  ArrowLeft,
  Loader2,
  AlertCircle,
  Users,
  Briefcase,
  GraduationCap,
  BookOpen,
  Plus,
  X,
  Check,
  XCircle,
  Edit,
  Trash2,
} from 'lucide-react'
import { Link } from 'react-router-dom'
import { useState, useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'

const STATUS_COLORS: Record<string, 'default' | 'secondary' | 'outline'> = {
  UPCOMING: 'outline',
  OPEN: 'default',
  ACTIVE: 'default',
  COMPLETED: 'secondary',
  CANCELLED: 'secondary',
}

// Tab components
function OverviewTab({ batch }: { batch: any }) {
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

function TrainersTab({ batchId, trainers, assignedTrainerIds }: any) {
  const queryClient = useQueryClient()
  const { showSuccess, showError } = useToastNotifications()
  const [selectedTrainers, setSelectedTrainers] = useState<number[]>([])

  const { data: allTrainers, isLoading } = useQuery({
    queryKey: ['admin', 'trainers'],
    queryFn: getTrainers,
  })

  // Fetch assigned trainers
  const { data: assignedTrainers } = useQuery({
    queryKey: ['admin', 'batches', batchId, 'trainers'],
    queryFn: () => getBatchTrainers(batchId),
  })

  // Update selectedTrainers when assignedTrainers data arrives
  useEffect(() => {
    if (assignedTrainers) {
      setSelectedTrainers(assignedTrainers.map(t => t.id))
    }
  }, [assignedTrainers])

  const assignMutation = useMutation({
    mutationFn: (trainerIds: number[]) => assignTrainersToBatch(batchId, trainerIds),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId] })
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches'] })
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId, 'trainers'] })
      showSuccess('Trainers assigned successfully!')
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to assign trainers')
    },
  })

  const unassignMutation = useMutation({
    mutationFn: (trainerId: number) => unassignTrainerFromBatch(batchId, trainerId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId] })
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches'] })
    },
  })

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
              {allTrainers?.map((trainer) => (
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

function CompaniesTab({ batchId, companies, linkedCompanyIds }: any) {
  const queryClient = useQueryClient()
  const { showSuccess, showError } = useToastNotifications()
  const [selectedCompanies, setSelectedCompanies] = useState<number[]>([])

  const { data: allCompanies, isLoading } = useQuery({
    queryKey: ['admin', 'companies'],
    queryFn: getCompanies,
  })

  // Fetch assigned companies
  const { data: assignedCompanies } = useQuery({
    queryKey: ['admin', 'batches', batchId, 'companies'],
    queryFn: () => getBatchCompanies(batchId),
  })

  // Update selectedCompanies when assignedCompanies data arrives
  useEffect(() => {
    if (assignedCompanies) {
      setSelectedCompanies(assignedCompanies.map(c => c.id))
    }
  }, [assignedCompanies])

  const mapMutation = useMutation({
    mutationFn: (companyIds: number[]) => mapCompaniesToBatch(batchId, companyIds),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId] })
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches'] })
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId, 'companies'] })
      showSuccess('Companies mapped successfully!')
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to map companies')
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
              {allCompanies?.map((company) => (
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
    mutationFn: (enrollmentId: number) => approveEnrollment(batchId, enrollmentId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId, 'enrollments'] })
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId] })
      showSuccess('Enrollment approved successfully!')
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to approve enrollment')
    },
  })

  const rejectMutation = useMutation({
    mutationFn: (enrollmentId: number) => rejectEnrollment(batchId, enrollmentId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId, 'enrollments'] })
      showSuccess('Enrollment rejected')
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to reject enrollment')
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
                        {enrollment.student?.user.email || 'N/A'}
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
                        {enrollment.student?.user.email || 'N/A'}
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
                        {enrollment.student?.user.email || 'N/A'}
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

const topicSchema = z.object({
  title: z.string().min(1, 'Title is required'),
  description: z.string().optional(),
  difficulty: z.enum(['BEGINNER', 'INTERMEDIATE', 'ADVANCED']).optional(),
  estimatedHours: z.string().optional().refine(
    (val) => !val || (!isNaN(Number(val)) && Number(val) > 0),
    'Must be a positive number'
  ),
  order: z.string().refine((val) => !isNaN(Number(val)) && Number(val) > 0, 'Must be a positive number'),
})

function SyllabusTab({ batchId, syllabus }: any) {
  const queryClient = useQueryClient()
  const { showSuccess, showError } = useToastNotifications()
  const [isAddingTopic, setIsAddingTopic] = useState(false)
  const [editingTopicId, setEditingTopicId] = useState<number | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
    setValue,
    watch,
  } = useForm<z.infer<typeof topicSchema>>({
    resolver: zodResolver(topicSchema),
    defaultValues: {
      title: '',
      description: '',
      difficulty: undefined,
      estimatedHours: '',
      order: '1',
    },
  })

  const createSyllabusMutation = useMutation({
    mutationFn: (data: { title: string; description?: string }) =>
      createSyllabus(batchId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId] })
      showSuccess('Syllabus created successfully!')
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to create syllabus')
    },
  })

  const addTopicMutation = useMutation({
    mutationFn: (data: any) => addSyllabusTopic(batchId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId] })
      reset()
      setIsAddingTopic(false)
      showSuccess('Topic added successfully!')
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to add topic')
    },
  })

  const updateTopicMutation = useMutation({
    mutationFn: ({ topicId, data }: { topicId: number; data: any }) =>
      updateSyllabusTopic(batchId, topicId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId] })
      setEditingTopicId(null)
      reset()
      showSuccess('Topic updated successfully!')
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to update topic')
    },
  })

  const deleteTopicMutation = useMutation({
    mutationFn: (topicId: number) => deleteSyllabusTopic(batchId, topicId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches', batchId] })
      showSuccess('Topic deleted successfully!')
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to delete topic')
    },
  })

  const onSubmitTopic = (data: z.infer<typeof topicSchema>) => {
    const payload = {
      title: data.title,
      description: data.description || undefined,
      difficulty: data.difficulty,
      estimatedHours: data.estimatedHours ? parseInt(data.estimatedHours) : undefined,
      order: parseInt(data.order),
    }

    if (editingTopicId) {
      updateTopicMutation.mutate({ topicId: editingTopicId, data: payload })
    } else {
      addTopicMutation.mutate(payload)
    }
  }

  const handleEdit = (topic: any) => {
    setEditingTopicId(topic.id)
    setValue('title', topic.title)
    setValue('description', topic.description || '')
    setValue('difficulty', topic.difficulty)
    setValue('estimatedHours', topic.estimatedHours?.toString() || '')
    setValue('order', topic.order.toString())
    setIsAddingTopic(true)
  }

  const handleCancel = () => {
    setIsAddingTopic(false)
    setEditingTopicId(null)
    reset()
  }

  const handleDelete = (topicId: number) => {
    if (confirm('Delete this topic?')) {
      deleteTopicMutation.mutate(topicId)
    }
  }

  if (!syllabus) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Create Syllabus</CardTitle>
          <CardDescription>Create a syllabus for this batch</CardDescription>
        </CardHeader>
        <CardContent>
          <form
            onSubmit={handleSubmit((data) => {
              createSyllabusMutation.mutate({
                title: data.title,
                description: data.description || undefined,
              })
            })}
            className="space-y-4"
          >
            <div className="space-y-2">
              <Label htmlFor="syllabus-title">Syllabus Title</Label>
              <Input
                id="syllabus-title"
                {...register('title')}
                placeholder="Full Stack Development Syllabus"
              />
              {errors.title && (
                <p className="text-sm text-destructive">{errors.title.message}</p>
              )}
            </div>
            <Button type="submit" disabled={createSyllabusMutation.isPending}>
              {createSyllabusMutation.isPending ? (
                <>
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  Creating...
                </>
              ) : (
                'Create Syllabus'
              )}
            </Button>
          </form>
        </CardContent>
      </Card>
    )
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>{syllabus.title}</CardTitle>
          {syllabus.description && (
            <CardDescription>{syllabus.description}</CardDescription>
          )}
        </CardHeader>
        <CardContent>
          <div className="space-y-4">
            {!isAddingTopic && (
              <Button onClick={() => setIsAddingTopic(true)}>
                <Plus className="mr-2 h-4 w-4" />
                Add Topic
              </Button>
            )}

            {isAddingTopic && (
              <Card>
                <CardHeader>
                  <CardTitle>
                    {editingTopicId ? 'Edit Topic' : 'Add New Topic'}
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <form onSubmit={handleSubmit(onSubmitTopic)} className="space-y-4">
                    <div className="space-y-2">
                      <Label htmlFor="topic-title">Title *</Label>
                      <Input id="topic-title" {...register('title')} />
                      {errors.title && (
                        <p className="text-sm text-destructive">{errors.title.message}</p>
                      )}
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="topic-description">Description</Label>
                      <Textarea id="topic-description" {...register('description')} rows={3} />
                    </div>
                    <div className="grid grid-cols-3 gap-4">
                      <div className="space-y-2">
                        <Label htmlFor="topic-difficulty">Difficulty</Label>
                        <Select
                          onValueChange={(value) => setValue('difficulty', value as any)}
                          value={watch('difficulty')}
                        >
                          <SelectTrigger id="topic-difficulty">
                            <SelectValue placeholder="Select" />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectItem value="BEGINNER">Beginner</SelectItem>
                            <SelectItem value="INTERMEDIATE">Intermediate</SelectItem>
                            <SelectItem value="ADVANCED">Advanced</SelectItem>
                          </SelectContent>
                        </Select>
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor="topic-hours">Estimated Hours</Label>
                        <Input
                          id="topic-hours"
                          type="number"
                          {...register('estimatedHours')}
                          min="1"
                        />
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor="topic-order">Order *</Label>
                        <Input id="topic-order" type="number" {...register('order')} min="1" />
                        {errors.order && (
                          <p className="text-sm text-destructive">{errors.order.message}</p>
                        )}
                      </div>
                    </div>
                    <div className="flex gap-2">
                      <Button type="submit" disabled={addTopicMutation.isPending}>
                        {addTopicMutation.isPending ? (
                          <>
                            <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                            Saving...
                          </>
                        ) : (
                          'Save Topic'
                        )}
                      </Button>
                      <Button type="button" variant="outline" onClick={handleCancel}>
                        Cancel
                      </Button>
                    </div>
                  </form>
                </CardContent>
              </Card>
            )}

            {syllabus.topics && syllabus.topics.length > 0 ? (
              <div className="space-y-2">
                {[...syllabus.topics]
                  .sort((a, b) => a.order - b.order)
                  .map((topic: any) => (
                    <Card key={topic.id}>
                      <CardContent className="pt-6">
                        <div className="flex items-start justify-between">
                          <div className="flex-1">
                            <div className="flex items-center gap-2">
                              <Badge variant="outline">#{topic.order}</Badge>
                              <h4 className="font-semibold">{topic.title}</h4>
                              {topic.difficulty && (
                                <Badge variant="secondary" className="ml-2">
                                  {topic.difficulty}
                                </Badge>
                              )}
                              {topic.estimatedHours && (
                                <span className="text-sm text-muted-foreground">
                                  {topic.estimatedHours}h
                                </span>
                              )}
                            </div>
                            {topic.description && (
                              <p className="text-sm text-muted-foreground mt-2">
                                {topic.description}
                              </p>
                            )}
                          </div>
                          <div className="flex gap-2">
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => handleEdit(topic)}
                            >
                              <Edit className="h-4 w-4" />
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => handleDelete(topic.id)}
                              disabled={deleteTopicMutation.isPending}
                            >
                              <Trash2 className="h-4 w-4" />
                            </Button>
                          </div>
                        </div>
                      </CardContent>
                    </Card>
                  ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground text-center py-8">
                No topics added yet. Click "Add Topic" to get started.
              </p>
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  )
}

export function BatchDetails() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
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
                  Trainers ({batch.trainers?.length || 0})
                </TabsTrigger>
                <TabsTrigger value="companies">
                  Companies ({batch.companies?.length || 0})
                </TabsTrigger>
                <TabsTrigger value="enrollments">
                  Enrollments ({batch.enrolledCount || 0})
                </TabsTrigger>
                <TabsTrigger value="syllabus">Syllabus</TabsTrigger>
              </TabsList>

              <TabsContent value="overview">
                <OverviewTab batch={batch} />
              </TabsContent>

              <TabsContent value="trainers">
                <TrainersTab
                  batchId={batchId}
                  trainers={batch.trainers}
                  assignedTrainerIds={batch.trainers?.map((t) => t.id) || []}
                />
              </TabsContent>

              <TabsContent value="companies">
                <CompaniesTab
                  batchId={batchId}
                  companies={batch.companies}
                  linkedCompanyIds={batch.companies?.map((c) => c.id) || []}
                />
              </TabsContent>

              <TabsContent value="enrollments">
                <EnrollmentsTab batchId={batchId} />
              </TabsContent>

              <TabsContent value="syllabus">
                <SyllabusTab batchId={batchId} syllabus={batch.syllabus} />
              </TabsContent>
            </Tabs>
          </div>
        </PageWrapper>
      </AuthenticatedLayout>
    </RoleGuard>
  )
}

