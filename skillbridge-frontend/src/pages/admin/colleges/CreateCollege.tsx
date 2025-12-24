/**
 * Create College Page
 * 
 * System Admin page to create a new college
 * Features:
 * - Form with validation
 * - Required fields: Name, Code
 * - Optional fields: Email, Phone, Address
 * - Success redirect to colleges list
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
  Alert,
  AlertDescription,
} from '@/shared/components/ui'
import { createCollege, type CreateCollegeRequest } from '@/api/admin'
import { AlertCircle, Loader2, ArrowLeft } from 'lucide-react'
import { Link } from 'react-router-dom'

// Form schema
const createCollegeSchema = z.object({
  name: z.string().min(1, 'College name is required').max(255, 'Name is too long'),
  code: z
    .string()
    .min(1, 'College code is required')
    .max(50, 'Code is too long')
    .regex(/^[A-Z0-9_-]+$/, 'Code must contain only uppercase letters, numbers, hyphens, or underscores'),
  email: z.string().email('Invalid email address').optional().or(z.literal('')),
  phone: z.string().max(20, 'Phone number is too long').optional().or(z.literal('')),
  address: z.string().max(500, 'Address is too long').optional().or(z.literal('')),
})

type CreateCollegeFormData = z.infer<typeof createCollegeSchema>

export function CreateCollege() {
  const navigate = useNavigate()

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<CreateCollegeFormData>({
    resolver: zodResolver(createCollegeSchema),
    defaultValues: {
      name: '',
      code: '',
      email: '',
      phone: '',
      address: '',
    },
  })

  const mutation = useMutation({
    mutationFn: (data: CreateCollegeRequest) => createCollege(data),
    onSuccess: () => {
      navigate('/admin/colleges', {
        state: { message: 'College created successfully!' },
      })
    },
  })

  const onSubmit = (data: CreateCollegeFormData) => {
    const payload: CreateCollegeRequest = {
      name: data.name,
      code: data.code.toUpperCase(), // Ensure uppercase
      email: data.email || undefined,
      phone: data.phone || undefined,
      address: data.address || undefined,
    }
    mutation.mutate(payload)
  }

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
                <h1 className="text-3xl font-bold tracking-tight">Create College</h1>
                <p className="text-muted-foreground">
                  Add a new college or institution to the platform
                </p>
              </div>
            </div>

            {/* Form Card */}
            <Card>
              <CardHeader>
                <CardTitle>College Information</CardTitle>
                <CardDescription>
                  Enter the details for the new college. Name and code are required.
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
                          'Failed to create college. Please try again.'}
                      </AlertDescription>
                    </Alert>
                  )}

                  {/* Name */}
                  <div className="space-y-2">
                    <Label htmlFor="name">
                      College Name <span className="text-destructive">*</span>
                    </Label>
                    <Input
                      id="name"
                      placeholder="Massachusetts Institute of Technology"
                      {...register('name')}
                      disabled={mutation.isPending}
                    />
                    {errors.name && (
                      <p className="text-sm text-destructive">{errors.name.message}</p>
                    )}
                  </div>

                  {/* Code */}
                  <div className="space-y-2">
                    <Label htmlFor="code">
                      College Code <span className="text-destructive">*</span>
                    </Label>
                    <Input
                      id="code"
                      placeholder="MIT"
                      {...register('code')}
                      disabled={mutation.isPending}
                      className="uppercase"
                      onChange={(e) => {
                        e.target.value = e.target.value.toUpperCase()
                        register('code').onChange(e)
                      }}
                    />
                    {errors.code && (
                      <p className="text-sm text-destructive">{errors.code.message}</p>
                    )}
                    <p className="text-xs text-muted-foreground">
                      Unique identifier for the college (uppercase letters, numbers, hyphens, or
                      underscores only)
                    </p>
                  </div>

                  {/* Email */}
                  <div className="space-y-2">
                    <Label htmlFor="email">Email</Label>
                    <Input
                      id="email"
                      type="email"
                      placeholder="contact@college.edu"
                      {...register('email')}
                      disabled={mutation.isPending}
                    />
                    {errors.email && (
                      <p className="text-sm text-destructive">{errors.email.message}</p>
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

                  {/* Address */}
                  <div className="space-y-2">
                    <Label htmlFor="address">Address</Label>
                    <Input
                      id="address"
                      placeholder="123 College Street, City, State, ZIP"
                      {...register('address')}
                      disabled={mutation.isPending}
                    />
                    {errors.address && (
                      <p className="text-sm text-destructive">{errors.address.message}</p>
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
                    <Button type="submit" disabled={mutation.isPending}>
                      {mutation.isPending ? (
                        <>
                          <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                          Creating...
                        </>
                      ) : (
                        'Create College'
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

