/**
 * Create Trainer Page
 * 
 * College Admin page to create a trainer account
 * Features:
 * - Form with validation
 * - Required fields: Email, Password, Full Name
 * - Optional fields: Phone, Department, Specialization, Bio
 * - Success redirect to trainers list
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
import { createTrainer, type CreateTrainerRequest } from '@/api/college-admin'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import { Loader2, ArrowLeft } from 'lucide-react'
import { Link } from 'react-router-dom'

// Form schema
const createTrainerSchema = z.object({
  email: z.string().email('Please enter a valid email address'),
  password: z
    .string()
    .min(8, 'Password must be at least 8 characters')
    .regex(
      /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]/,
      'Password must contain uppercase, lowercase, number, and special character'
    ),
  confirmPassword: z.string(),
  fullName: z.string().min(1, 'Full name is required').max(255, 'Name is too long'),
  phone: z.string().max(20, 'Phone number is too long').optional().or(z.literal('')),
  department: z.string().max(255, 'Department name is too long').optional().or(z.literal('')),
  specialization: z.string().max(255, 'Specialization is too long').optional().or(z.literal('')),
  bio: z.string().max(1000, 'Bio is too long').optional().or(z.literal('')),
}).refine((data) => data.password === data.confirmPassword, {
  message: "Passwords don't match",
  path: ['confirmPassword'],
})

type CreateTrainerFormData = z.infer<typeof createTrainerSchema>

export function CreateTrainer() {
  const navigate = useNavigate()
  const { showSuccess, showError } = useToastNotifications()

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<CreateTrainerFormData>({
    resolver: zodResolver(createTrainerSchema),
    defaultValues: {
      email: '',
      password: '',
      confirmPassword: '',
      fullName: '',
      phone: '',
      department: '',
      specialization: '',
      bio: '',
    },
  })

  const mutation = useMutation({
    mutationFn: (data: CreateTrainerRequest) => createTrainer(data),
    onSuccess: () => {
      showSuccess('Trainer created successfully!')
      navigate('/admin/trainers')
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to create trainer. Please try again.')
    },
  })

  const onSubmit = (data: CreateTrainerFormData) => {
    const payload: CreateTrainerRequest = {
      email: data.email,
      password: data.password,
      fullName: data.fullName,
      phone: data.phone || undefined,
      department: data.department || undefined,
      specialization: data.specialization || undefined,
      bio: data.bio || undefined,
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
                <Link to="/admin/trainers">
                  <ArrowLeft className="h-4 w-4" />
                </Link>
              </Button>
              <div>
                <h1 className="text-3xl font-bold tracking-tight">Add Trainer</h1>
                <p className="text-muted-foreground">
                  Create a new trainer account for your college
                </p>
              </div>
            </div>

            {/* Form Card */}
            <Card>
              <CardHeader>
                <CardTitle>Trainer Account Information</CardTitle>
                <CardDescription>
                  Create a new trainer account. The trainer will be able to manage assigned batches
                  and update student progress.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <form onSubmit={handleSubmit(onSubmit)} className="space-y-6">
                  {/* Full Name */}
                  <div className="space-y-2">
                    <Label htmlFor="fullName">
                      Full Name <span className="text-destructive">*</span>
                    </Label>
                    <Input
                      id="fullName"
                      placeholder="John Doe"
                      {...register('fullName')}
                      disabled={mutation.isPending}
                    />
                    {errors.fullName && (
                      <p className="text-sm text-destructive">{errors.fullName.message}</p>
                    )}
                  </div>

                  {/* Email */}
                  <div className="space-y-2">
                    <Label htmlFor="email">
                      Email <span className="text-destructive">*</span>
                    </Label>
                    <Input
                      id="email"
                      type="email"
                      placeholder="trainer@college.edu"
                      {...register('email')}
                      disabled={mutation.isPending}
                      autoComplete="email"
                    />
                    {errors.email && (
                      <p className="text-sm text-destructive">{errors.email.message}</p>
                    )}
                  </div>

                  {/* Password */}
                  <div className="space-y-2">
                    <Label htmlFor="password">
                      Password <span className="text-destructive">*</span>
                    </Label>
                    <Input
                      id="password"
                      type="password"
                      placeholder="Enter password"
                      {...register('password')}
                      disabled={mutation.isPending}
                      autoComplete="new-password"
                    />
                    {errors.password && (
                      <p className="text-sm text-destructive">{errors.password.message}</p>
                    )}
                    <p className="text-xs text-muted-foreground">
                      Must be at least 8 characters with uppercase, lowercase, number, and special
                      character
                    </p>
                  </div>

                  {/* Confirm Password */}
                  <div className="space-y-2">
                    <Label htmlFor="confirmPassword">
                      Confirm Password <span className="text-destructive">*</span>
                    </Label>
                    <Input
                      id="confirmPassword"
                      type="password"
                      placeholder="Confirm password"
                      {...register('confirmPassword')}
                      disabled={mutation.isPending}
                      autoComplete="new-password"
                    />
                    {errors.confirmPassword && (
                      <p className="text-sm text-destructive">
                        {errors.confirmPassword.message}
                      </p>
                    )}
                  </div>

                  {/* Phone */}
                  <div className="space-y-2">
                    <Label htmlFor="phone">Phone</Label>
                    <Input
                      id="phone"
                      type="tel"
                      placeholder="+1 (555) 123-4567"
                      {...register('phone')}
                      disabled={mutation.isPending}
                    />
                    {errors.phone && (
                      <p className="text-sm text-destructive">{errors.phone.message}</p>
                    )}
                  </div>

                  {/* Department */}
                  <div className="space-y-2">
                    <Label htmlFor="department">Department</Label>
                    <Input
                      id="department"
                      placeholder="Computer Science"
                      {...register('department')}
                      disabled={mutation.isPending}
                    />
                    {errors.department && (
                      <p className="text-sm text-destructive">{errors.department.message}</p>
                    )}
                  </div>

                  {/* Specialization */}
                  <div className="space-y-2">
                    <Label htmlFor="specialization">Specialization</Label>
                    <Input
                      id="specialization"
                      placeholder="Full Stack Development, Machine Learning, etc."
                      {...register('specialization')}
                      disabled={mutation.isPending}
                    />
                    {errors.specialization && (
                      <p className="text-sm text-destructive">{errors.specialization.message}</p>
                    )}
                  </div>

                  {/* Bio */}
                  <div className="space-y-2">
                    <Label htmlFor="bio">Bio</Label>
                    <Textarea
                      id="bio"
                      placeholder="Brief bio about the trainer..."
                      {...register('bio')}
                      disabled={mutation.isPending}
                      rows={4}
                    />
                    {errors.bio && (
                      <p className="text-sm text-destructive">{errors.bio.message}</p>
                    )}
                  </div>

                  {/* Actions */}
                  <div className="flex justify-end gap-4 pt-4">
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => navigate('/admin/trainers')}
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
                        'Create Trainer'
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

