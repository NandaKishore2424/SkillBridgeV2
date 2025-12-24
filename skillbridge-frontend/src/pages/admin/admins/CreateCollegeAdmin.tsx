/**
 * Create College Admin Page
 * 
 * System Admin page to create a college admin account
 * Features:
 * - College selection dropdown
 * - Form with validation
 * - Required fields: Email, Password, Full Name
 * - Optional field: Phone
 * - Success message and redirect
 */

import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useQuery, useMutation } from '@tanstack/react-query'
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
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Alert,
  AlertDescription,
} from '@/shared/components/ui'
import {
  createCollegeAdmin,
  type CreateCollegeAdminRequest,
} from '@/api/admin'
import { getAllColleges } from '@/api/admin'
import { AlertCircle, Loader2, ArrowLeft, CheckCircle2 } from 'lucide-react'
import { Link } from 'react-router-dom'

// Form schema
const createAdminSchema = z.object({
  collegeId: z.number().min(1, 'Please select a college'),
  email: z.string().email('Please enter a valid email address'),
  password: z
    .string()
    .min(8, 'Password must be at least 8 characters')
    .regex(
      /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]/,
      'Password must contain uppercase, lowercase, number, and special character'
    ),
  confirmPassword: z.string(),
  fullName: z.string().min(1, 'Full name is required'),
  phone: z.string().max(20, 'Phone number is too long').optional().or(z.literal('')),
}).refine((data) => data.password === data.confirmPassword, {
  message: "Passwords don't match",
  path: ['confirmPassword'],
})

type CreateAdminFormData = z.infer<typeof createAdminSchema>

export function CreateCollegeAdmin() {
  const navigate = useNavigate()
  const [success, setSuccess] = useState(false)

  // Fetch active colleges
  const {
    data: colleges,
    isLoading: collegesLoading,
    error: collegesError,
  } = useQuery({
    queryKey: ['admin', 'colleges'],
    queryFn: getAllColleges,
    select: (data) => data.filter((college) => college.status === 'ACTIVE'),
  })

  const {
    register,
    handleSubmit,
    formState: { errors },
    setValue,
    watch,
  } = useForm<CreateAdminFormData>({
    resolver: zodResolver(createAdminSchema),
    defaultValues: {
      collegeId: undefined,
      email: '',
      password: '',
      confirmPassword: '',
      fullName: '',
      phone: '',
    },
  })

  const mutation = useMutation({
    mutationFn: ({ collegeId, data }: { collegeId: number; data: CreateCollegeAdminRequest }) =>
      createCollegeAdmin(collegeId, data),
    onSuccess: () => {
      setSuccess(true)
      setTimeout(() => {
        navigate('/admin/colleges')
      }, 2000)
    },
  })

  const onSubmit = (data: CreateAdminFormData) => {
    const payload: CreateCollegeAdminRequest = {
      email: data.email,
      password: data.password,
      fullName: data.fullName,
      phone: data.phone || undefined,
    }
    mutation.mutate({ collegeId: data.collegeId, data: payload })
  }

  const selectedCollegeId = watch('collegeId')

  return (
    <RoleGuard allowedRoles={['SYSTEM_ADMIN']}>
      <AuthenticatedLayout>
        <PageWrapper maxWidth="lg">
          <div className="space-y-6">
            {/* Header */}
            <div className="flex items-center gap-4">
              <Button variant="ghost" size="icon" asChild>
                <Link to="/admin/colleges">
                  <ArrowLeft className="h-4 w-4" />
                </Link>
              </Button>
              <div>
                <h1 className="text-3xl font-bold tracking-tight">Create College Admin</h1>
                <p className="text-muted-foreground">
                  Create an administrator account for a college
                </p>
              </div>
            </div>

            {/* Success Alert */}
            {success && (
              <Alert className="border-green-500 bg-green-50 dark:bg-green-950">
                <CheckCircle2 className="h-4 w-4 text-green-600" />
                <AlertDescription className="text-green-800 dark:text-green-200">
                  College admin created successfully! Redirecting...
                </AlertDescription>
              </Alert>
            )}

            {/* Form Card */}
            <Card>
              <CardHeader>
                <CardTitle>Admin Account Information</CardTitle>
                <CardDescription>
                  Create a new administrator account. Only one admin per college is allowed.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <form onSubmit={handleSubmit(onSubmit)} className="space-y-6">
                  {/* Error Alert */}
                  {mutation.error && (
                    <Alert variant="destructive">
                      <AlertCircle className="h-4 w-4" />
                      <AlertDescription>
                        {(mutation.error as any)?.response?.data?.message ||
                          'Failed to create college admin. Please try again.'}
                      </AlertDescription>
                    </Alert>
                  )}

                  {/* College Selection */}
                  <div className="space-y-2">
                    <Label htmlFor="collegeId">
                      College <span className="text-destructive">*</span>
                    </Label>
                    <Select
                      onValueChange={(value) => setValue('collegeId', parseInt(value))}
                      disabled={collegesLoading || mutation.isPending}
                    >
                      <SelectTrigger id="collegeId">
                        <SelectValue placeholder="Select a college" />
                      </SelectTrigger>
                      <SelectContent>
                        {collegesLoading ? (
                          <SelectItem value="loading" disabled>
                            Loading colleges...
                          </SelectItem>
                        ) : collegesError ? (
                          <SelectItem value="error" disabled>
                            Error loading colleges
                          </SelectItem>
                        ) : colleges && colleges.length > 0 ? (
                          colleges.map((college) => (
                            <SelectItem key={college.id} value={college.id.toString()}>
                              {college.name} ({college.code})
                            </SelectItem>
                          ))
                        ) : (
                          <SelectItem value="empty" disabled>
                            No active colleges available
                          </SelectItem>
                        )}
                      </SelectContent>
                    </Select>
                    {errors.collegeId && (
                      <p className="text-sm text-destructive">{errors.collegeId.message}</p>
                    )}
                    {selectedCollegeId && colleges && (
                      <p className="text-xs text-muted-foreground">
                        Selected: {colleges.find((c) => c.id === selectedCollegeId)?.name}
                      </p>
                    )}
                  </div>

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
                      placeholder="admin@college.edu"
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

                  {/* Actions */}
                  <div className="flex justify-end gap-4 pt-4">
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => navigate('/admin/colleges')}
                      disabled={mutation.isPending}
                    >
                      Cancel
                    </Button>
                    <Button type="submit" disabled={mutation.isPending || success}>
                      {mutation.isPending ? (
                        <>
                          <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                          Creating...
                        </>
                      ) : (
                        'Create Admin'
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

