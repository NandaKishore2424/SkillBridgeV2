/**
 * Create College Admin Modal
 * 
 * Modal dialog for creating a college admin account
 * Used within College Detail page
 */

import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation } from '@tanstack/react-query'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Button,
  Input,
  Label,
} from '@/shared/components/ui'
import { createCollegeAdmin, type CreateCollegeAdminRequest } from '@/api/admin'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import { Loader2 } from 'lucide-react'

const createAdminSchema = z.object({
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

interface CreateCollegeAdminModalProps {
  collegeId: number
  collegeName: string
  open: boolean
  onClose: () => void
  onSuccess: () => void
}

export function CreateCollegeAdminModal({
  collegeId,
  collegeName,
  open,
  onClose,
  onSuccess,
}: CreateCollegeAdminModalProps) {
  const { showError } = useToastNotifications()

  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
  } = useForm<CreateAdminFormData>({
    resolver: zodResolver(createAdminSchema),
    defaultValues: {
      email: '',
      password: '',
      confirmPassword: '',
      fullName: '',
      phone: '',
    },
  })

  const mutation = useMutation({
    mutationFn: (data: CreateCollegeAdminRequest) => createCollegeAdmin(collegeId, data),
    onSuccess: () => {
      reset()
      onSuccess()
    },
    onError: (error: any) => {
      showError('Failed to create admin.', error?.response?.data?.message || error.message)
    },
  })

  const onSubmit = (data: CreateAdminFormData) => {
    const requestData: CreateCollegeAdminRequest = {
      email: data.email,
      password: data.password,
      fullName: data.fullName,
      phone: data.phone || undefined,
    }
    mutation.mutate(requestData)
  }

  const handleClose = () => {
    if (!mutation.isPending) {
      reset()
      onClose()
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-[500px]">
        <DialogHeader>
          <DialogTitle>Create College Admin</DialogTitle>
          <DialogDescription>
            Create an administrator account for <strong>{collegeName}</strong>. This admin will be able to manage batches, students, trainers, and companies for this college.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="fullName">Full Name *</Label>
            <Input
              id="fullName"
              {...register('fullName')}
              placeholder="John Doe"
              disabled={mutation.isPending}
            />
            {errors.fullName && (
              <p className="text-sm text-destructive">{errors.fullName.message}</p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="email">Email *</Label>
            <Input
              id="email"
              type="email"
              {...register('email')}
              placeholder="admin@college.edu"
              disabled={mutation.isPending}
            />
            {errors.email && (
              <p className="text-sm text-destructive">{errors.email.message}</p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="phone">Phone</Label>
            <Input
              id="phone"
              type="tel"
              {...register('phone')}
              placeholder="+1 234 567 8900"
              disabled={mutation.isPending}
            />
            {errors.phone && (
              <p className="text-sm text-destructive">{errors.phone.message}</p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="password">Password *</Label>
            <Input
              id="password"
              type="password"
              {...register('password')}
              placeholder="Enter password"
              disabled={mutation.isPending}
            />
            {errors.password && (
              <p className="text-sm text-destructive">{errors.password.message}</p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="confirmPassword">Confirm Password *</Label>
            <Input
              id="confirmPassword"
              type="password"
              {...register('confirmPassword')}
              placeholder="Confirm password"
              disabled={mutation.isPending}
            />
            {errors.confirmPassword && (
              <p className="text-sm text-destructive">{errors.confirmPassword.message}</p>
            )}
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={handleClose}
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
                'Create Admin'
              )}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

