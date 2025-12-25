/**
 * Create Batch Page
 * 
 * College Admin page to create a new training batch
 * Features:
 * - Form with validation
 * - Required fields: Name
 * - Optional fields: Description, Start Date, End Date, Max Enrollments
 * - Success redirect to batches list
 */

import { useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation } from '@tanstack/react-query'
import { AuthenticatedLayout } from '@/shared/components/layout'
import { PageWrapper } from '@/shared/components/layout'
import { RoleGuard } from '@/shared/components/auth'
import {
  Button,
  Input,
  Label,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Textarea,
} from '@/shared/components/ui'
import { createBatch, type CreateBatchRequest } from '@/api/college-admin'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import { Loader2, ArrowLeft } from 'lucide-react'
import { Link } from 'react-router-dom'

// Form schema
const createBatchSchema = z.object({
  name: z.string().min(1, 'Batch name is required').max(255, 'Name is too long'),
  description: z.string().max(1000, 'Description is too long').optional().or(z.literal('')),
  startDate: z.string().optional().or(z.literal('')),
  endDate: z.string().optional().or(z.literal('')),
  maxEnrollments: z
    .string()
    .optional()
    .refine(
      (val) => !val || (!isNaN(Number(val)) && Number(val) > 0),
      'Must be a positive number'
    ),
}).refine(
  (data) => {
    if (!data.startDate || !data.endDate) return true
    return new Date(data.startDate) <= new Date(data.endDate)
  },
  {
    message: 'End date must be after start date',
    path: ['endDate'],
  }
)

type CreateBatchFormData = z.infer<typeof createBatchSchema>

export function CreateBatch() {
  const navigate = useNavigate()
  const { showSuccess, showError } = useToastNotifications()

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<CreateBatchFormData>({
    resolver: zodResolver(createBatchSchema),
    defaultValues: {
      name: '',
      description: '',
      startDate: '',
      endDate: '',
      maxEnrollments: '',
    },
  })

  const mutation = useMutation({
    mutationFn: (data: CreateBatchRequest) => createBatch(data),
    onSuccess: () => {
      showSuccess('Batch created successfully!')
      navigate('/admin/batches')
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to create batch. Please try again.')
    },
  })

  const onSubmit = (data: CreateBatchFormData) => {
    const payload: CreateBatchRequest = {
      name: data.name,
      description: data.description || undefined,
      startDate: data.startDate || undefined,
      endDate: data.endDate || undefined,
      maxEnrollments: data.maxEnrollments ? parseInt(data.maxEnrollments) : undefined,
    }
    mutation.mutate(payload)
  }

  return (
    <RoleGuard allowedRoles={['COLLEGE_ADMIN']}>
      <AuthenticatedLayout>
        <PageWrapper maxWidth="lg">
          <div className="space-y-6">
            {/* Header */}
            <div className="flex items-center gap-4">
              <Button variant="ghost" size="icon" asChild>
                <Link to="/admin/batches">
                  <ArrowLeft className="h-4 w-4" />
                </Link>
              </Button>
              <div>
                <h1 className="text-3xl font-bold tracking-tight">Create Batch</h1>
                <p className="text-muted-foreground">
                  Create a new training batch for your college
                </p>
              </div>
            </div>

            {/* Form Card */}
            <Card>
              <CardHeader>
                <CardTitle>Batch Information</CardTitle>
                <CardDescription>
                  Enter the details for the new training batch. You can add syllabus, assign
                  trainers, and map companies after creation.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <form onSubmit={handleSubmit(onSubmit)} className="space-y-6">
                  {/* Name */}
                  <div className="space-y-2">
                    <Label htmlFor="name">
                      Batch Name <span className="text-destructive">*</span>
                    </Label>
                    <Input
                      id="name"
                      placeholder="Full Stack Development - Spring 2024"
                      {...register('name')}
                      disabled={mutation.isPending}
                    />
                    {errors.name && (
                      <p className="text-sm text-destructive">{errors.name.message}</p>
                    )}
                  </div>

                  {/* Description */}
                  <div className="space-y-2">
                    <Label htmlFor="description">Description</Label>
                    <Textarea
                      id="description"
                      placeholder="A comprehensive full-stack development program covering..."
                      {...register('description')}
                      disabled={mutation.isPending}
                      rows={4}
                    />
                    {errors.description && (
                      <p className="text-sm text-destructive">{errors.description.message}</p>
                    )}
                    <p className="text-xs text-muted-foreground">
                      Optional description of the batch content and objectives
                    </p>
                  </div>

                  {/* Dates */}
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    {/* Start Date */}
                    <div className="space-y-2">
                      <Label htmlFor="startDate">Start Date</Label>
                      <Input
                        id="startDate"
                        type="date"
                        {...register('startDate')}
                        disabled={mutation.isPending}
                      />
                      {errors.startDate && (
                        <p className="text-sm text-destructive">{errors.startDate.message}</p>
                      )}
                    </div>

                    {/* End Date */}
                    <div className="space-y-2">
                      <Label htmlFor="endDate">End Date</Label>
                      <Input
                        id="endDate"
                        type="date"
                        {...register('endDate')}
                        disabled={mutation.isPending}
                      />
                      {errors.endDate && (
                        <p className="text-sm text-destructive">{errors.endDate.message}</p>
                      )}
                    </div>
                  </div>

                  {/* Max Enrollments */}
                  <div className="space-y-2">
                    <Label htmlFor="maxEnrollments">Maximum Enrollments</Label>
                    <Input
                      id="maxEnrollments"
                      type="number"
                      placeholder="50"
                      min="1"
                      {...register('maxEnrollments')}
                      disabled={mutation.isPending}
                    />
                    {errors.maxEnrollments && (
                      <p className="text-sm text-destructive">
                        {errors.maxEnrollments.message}
                      </p>
                    )}
                    <p className="text-xs text-muted-foreground">
                      Optional limit on the number of students that can enroll
                    </p>
                  </div>

                  {/* Actions */}
                  <div className="flex justify-end gap-4 pt-4">
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => navigate('/admin/batches')}
                      disabled={mutation.isPending}
                    >
                      Cancel
                    </Button>
                    <Button type="submit" disabled={mutation.isPending}>
                      {mutation.isPending ? (
                        <>
                          <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                          Creating...
                        </>
                      ) : (
                        'Create Batch'
                      )}
                    </Button>
                  </div>
                </form>
              </CardContent>
            </Card>
          </div>
        </PageWrapper>
      </AuthenticatedLayout>
    </RoleGuard>
  )
}

