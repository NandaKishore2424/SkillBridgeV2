/**
 * Create Company Page
 * 
 * College Admin page to create a new company
 * Features:
 * - Form with validation
 * - Required fields: Name, Hiring Type
 * - Optional fields: Domain, Hiring Process, Notes
 * - Success redirect to companies list
 */

import { useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation, useQuery } from '@tanstack/react-query'
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
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/shared/components/ui'
import { createCompany, type CreateCompanyRequest } from '@/api/college-admin'
import { getAllColleges } from '@/api/admin'
import { useAuth } from '@/shared/hooks/useAuth'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import { Loader2, ArrowLeft } from 'lucide-react'
import { Link } from 'react-router-dom'

// Form schema
const createCompanySchema = z.object({
  name: z.string().min(1, 'Company name is required').max(255, 'Name is too long'),
  domain: z.string().max(255, 'Domain is too long').optional().or(z.literal('')),
  hiringType: z.enum(['FULL_TIME', 'INTERNSHIP', 'BOTH'], {
    required_error: 'Please select a hiring type',
  }),
  collegeId: z.number().optional(),
  hiringProcess: z.string().max(1000, 'Hiring process description is too long').optional().or(z.literal('')),
  notes: z.string().max(1000, 'Notes are too long').optional().or(z.literal('')),
})

type CreateCompanyFormData = z.infer<typeof createCompanySchema>

export function CreateCompany() {
  const navigate = useNavigate()
  const { showSuccess, showError } = useToastNotifications()
  const { user } = useAuth()
  
  const isSystemAdmin = user?.role === 'SYSTEM_ADMIN'

  // Fetch colleges for system admin
  const { data: colleges, isLoading: isLoadingColleges } = useQuery({
    queryKey: ['colleges'],
    queryFn: getAllColleges,
    enabled: isSystemAdmin,
  })

  const {
    register,
    handleSubmit,
    formState: { errors },
    setValue,
    watch,
  } = useForm<CreateCompanyFormData>({
    resolver: zodResolver(createCompanySchema),
    defaultValues: {
      name: '',
      domain: '',
      hiringType: undefined,
      collegeId: undefined,
      hiringProcess: '',
      notes: '',
    },
  })

  const mutation = useMutation({
    mutationFn: (data: CreateCompanyRequest) => createCompany(data),
    onSuccess: () => {
      showSuccess('Company created successfully!')
      navigate('/admin/companies')
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to create company. Please try again.')
    },
  })

  const onSubmit = (data: CreateCompanyFormData) => {
    const payload: CreateCompanyRequest = {
      name: data.name,
      domain: data.domain || undefined,
      hiringType: data.hiringType,
      collegeId: data.collegeId || undefined,
      hiringProcess: data.hiringProcess || undefined,
      notes: data.notes || undefined,
    }
    mutation.mutate(payload)
  }

  const hiringType = watch('hiringType')
  const selectedCollegeId = watch('collegeId')

  return (
    <RoleGuard allowedRoles={['COLLEGE_ADMIN', 'SYSTEM_ADMIN']}>
      <AuthenticatedLayout>
        <PageWrapper maxWidth="lg">
          <div className="space-y-6">
            {/* Header */}
            <div className="flex items-center gap-4">
              <Button variant="ghost" size="icon" asChild>
                <Link to="/admin/companies">
                  <ArrowLeft className="h-4 w-4" />
                </Link>
              </Button>
              <div>
                <h1 className="text-3xl font-bold tracking-tight">Add Company</h1>
                <p className="text-muted-foreground">
                  Add a new company to link with training batches
                </p>
              </div>
            </div>

            {/* Form Card */}
            <Card>
              <CardHeader>
                <CardTitle>Company Information</CardTitle>
                <CardDescription>
                  Enter the details for the new company. You can link it to batches after creation.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <form onSubmit={handleSubmit(onSubmit)} className="space-y-6">
                  {/* College Selection (System Admin only) */}
                  {isSystemAdmin && (
                    <div className="space-y-2">
                      <Label htmlFor="collegeId">
                        College <span className="text-destructive">*</span>
                      </Label>
                      <Select
                        onValueChange={(value) => setValue('collegeId', parseInt(value))}
                        disabled={mutation.isPending || isLoadingColleges}
                      >
                        <SelectTrigger id="collegeId">
                          <SelectValue placeholder="Select a college" />
                        </SelectTrigger>
                        <SelectContent>
                          {colleges?.map((college) => (
                            <SelectItem key={college.id} value={college.id.toString()}>
                              {college.name}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      {errors.collegeId && (
                        <p className="text-sm text-destructive">{errors.collegeId.message}</p>
                      )}
                      <p className="text-xs text-muted-foreground">
                        Select which college this company belongs to
                      </p>
                    </div>
                  )}

                  {/* Name */}
                  <div className="space-y-2">
                    <Label htmlFor="name">
                      Company Name <span className="text-destructive">*</span>
                    </Label>
                    <Input
                      id="name"
                      placeholder="Acme Corporation"
                      {...register('name')}
                      disabled={mutation.isPending}
                    />
                    {errors.name && (
                      <p className="text-sm text-destructive">{errors.name.message}</p>
                    )}
                  </div>

                  {/* Domain */}
                  <div className="space-y-2">
                    <Label htmlFor="domain">Website Domain</Label>
                    <Input
                      id="domain"
                      placeholder="acme.com"
                      {...register('domain')}
                      disabled={mutation.isPending}
                    />
                    {errors.domain && (
                      <p className="text-sm text-destructive">{errors.domain.message}</p>
                    )}
                    <p className="text-xs text-muted-foreground">
                      Company website domain (e.g., example.com)
                    </p>
                  </div>

                  {/* Hiring Type */}
                  <div className="space-y-2">
                    <Label htmlFor="hiringType">
                      Hiring Type <span className="text-destructive">*</span>
                    </Label>
                    <Select
                      onValueChange={(value) => setValue('hiringType', value as any)}
                      disabled={mutation.isPending}
                    >
                      <SelectTrigger id="hiringType">
                        <SelectValue placeholder="Select hiring type" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="FULL_TIME">Full Time Only</SelectItem>
                        <SelectItem value="INTERNSHIP">Internship Only</SelectItem>
                        <SelectItem value="BOTH">Both Full Time & Internship</SelectItem>
                      </SelectContent>
                    </Select>
                    {errors.hiringType && (
                      <p className="text-sm text-destructive">{errors.hiringType.message}</p>
                    )}
                  </div>

                  {/* Hiring Process */}
                  <div className="space-y-2">
                    <Label htmlFor="hiringProcess">Hiring Process</Label>
                    <Textarea
                      id="hiringProcess"
                      placeholder="Describe the hiring process steps..."
                      {...register('hiringProcess')}
                      disabled={mutation.isPending}
                      rows={4}
                    />
                    {errors.hiringProcess && (
                      <p className="text-sm text-destructive">{errors.hiringProcess.message}</p>
                    )}
                    <p className="text-xs text-muted-foreground">
                      Optional description of the company's hiring process
                    </p>
                  </div>

                  {/* Notes */}
                  <div className="space-y-2">
                    <Label htmlFor="notes">Notes</Label>
                    <Textarea
                      id="notes"
                      placeholder="Additional notes about the company..."
                      {...register('notes')}
                      disabled={mutation.isPending}
                      rows={3}
                    />
                    {errors.notes && (
                      <p className="text-sm text-destructive">{errors.notes.message}</p>
                    )}
                  </div>

                  {/* Actions */}
                  <div className="flex justify-end gap-4 pt-4">
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => navigate('/admin/companies')}
                      disabled={mutation.isPending}
                    >
                      Cancel
                    </Button>
                    <Button 
                      type="submit" 
                      disabled={
                        mutation.isPending || 
                        !hiringType || 
                        (isSystemAdmin && !selectedCollegeId)
                      }
                    >
                      {mutation.isPending ? (
                        <>
                          <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                          Creating...
                        </>
                      ) : (
                        'Create Company'
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

