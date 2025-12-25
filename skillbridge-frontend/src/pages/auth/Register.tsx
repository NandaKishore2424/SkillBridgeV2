/**
 * Register Page
 * 
 * Registration page for Students and Trainers
 * Features:
 * - Role selection (Student/Trainer)
 * - College selection dropdown
 * - Role-specific form fields
 * - Form validation (React Hook Form + Zod)
 * - Error handling
 * - Loading states
 */

import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '@/shared/hooks/useAuth'
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
  RadioGroup,
  RadioGroupItem,
} from '@/shared/components/ui'
import { Header } from '@/shared/components/layout'
import { PageWrapper } from '@/shared/components/layout'
import { AlertCircle, Loader2 } from 'lucide-react'
import { getColleges } from '@/api/college'
import type { RegisterData } from '@/shared/types/auth'

// Base registration schema (common fields)
const baseRegisterSchema = z.object({
  email: z.string().email('Please enter a valid email address'),
  password: z
    .string()
    .min(8, 'Password must be at least 8 characters')
    .regex(
      /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]/,
      'Password must contain uppercase, lowercase, number, and special character'
    ),
  confirmPassword: z.string(),
  role: z.enum(['STUDENT', 'TRAINER']),
  collegeId: z.number().min(1, 'Please select a college'),
})

// Student-specific schema
const studentSchema = baseRegisterSchema.extend({
  role: z.literal('STUDENT'),
  fullName: z.string().min(1, 'Full name is required'),
  rollNumber: z.string().min(1, 'Roll number is required'),
  degree: z.string().optional(),
  branch: z.string().optional(),
  year: z.number().min(1).max(10).optional(),
})

// Trainer-specific schema
const trainerSchema = baseRegisterSchema.extend({
  role: z.literal('TRAINER'),
  fullName: z.string().min(1, 'Full name is required'),
  department: z.string().optional(),
  specialization: z.string().optional(),
  bio: z.string().optional(),
})

// Dynamic schema based on role
const createRegisterSchema = (role: 'STUDENT' | 'TRAINER') => {
  const base = baseRegisterSchema.refine((data) => data.password === data.confirmPassword, {
    message: "Passwords don't match",
    path: ['confirmPassword'],
  })

  if (role === 'STUDENT') {
    return studentSchema.refine((data) => data.password === data.confirmPassword, {
      message: "Passwords don't match",
      path: ['confirmPassword'],
    })
  } else {
    return trainerSchema.refine((data) => data.password === data.confirmPassword, {
      message: "Passwords don't match",
      path: ['confirmPassword'],
    })
  }
}

type RegisterFormData = z.infer<typeof baseRegisterSchema> &
  Partial<z.infer<typeof studentSchema>> &
  Partial<z.infer<typeof trainerSchema>>

export function Register() {
  const navigate = useNavigate()
  const { register: registerUser, isLoading, error, clearError } = useAuth()
  const [selectedRole, setSelectedRole] = useState<'STUDENT' | 'TRAINER'>('STUDENT')
  const [localError, setLocalError] = useState<string | null>(null)

  // Fetch colleges for dropdown
  const {
    data: colleges,
    isLoading: collegesLoading,
    error: collegesError,
  } = useQuery({
    queryKey: ['colleges'],
    queryFn: getColleges,
  })

  const schema = createRegisterSchema(selectedRole)

  const {
    register,
    handleSubmit,
    formState: { errors },
    setValue,
    watch,
  } = useForm<RegisterFormData>({
    resolver: zodResolver(schema),
    defaultValues: {
      role: 'STUDENT',
      collegeId: undefined,
    },
  })

  // Watch role to update schema
  const watchedRole = watch('role')

  // Update role when radio button changes
  const handleRoleChange = (role: 'STUDENT' | 'TRAINER') => {
    setSelectedRole(role)
    setValue('role', role)
  }

  const onSubmit = async (data: RegisterFormData) => {
    try {
      setLocalError(null)
      clearError()

      const registerData: RegisterData = {
        email: data.email,
        password: data.password,
        confirmPassword: data.confirmPassword,
        role: data.role,
        collegeId: data.collegeId,
        // Student fields
        ...(data.role === 'STUDENT' && {
          fullName: data.fullName,
          rollNumber: data.rollNumber,
          degree: data.degree,
          branch: data.branch,
          year: data.year,
        }),
        // Trainer fields
        ...(data.role === 'TRAINER' && {
          fullName: data.fullName,
          department: data.department,
          specialization: data.specialization,
          bio: data.bio,
        }),
      }

      await registerUser(registerData)
      // Navigation is handled by AuthContext (redirects to login)
    } catch (error: any) {
      setLocalError(error?.response?.data?.message || 'Registration failed. Please try again.')
    }
  }

  const displayError = error || localError

  return (
    <div className="min-h-screen flex flex-col bg-background">
      <Header />
      <main className="flex-1 py-8">
        <PageWrapper maxWidth="lg" padding={false}>
          <Card className="w-full max-w-2xl mx-auto">
            <CardHeader className="space-y-1">
              <CardTitle className="text-2xl font-bold text-center">Create Account</CardTitle>
              <CardDescription className="text-center">
                Register as a Student or Trainer to get started
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={handleSubmit(onSubmit)} className="space-y-6">
                {/* Error Alert */}
                {displayError && (
                  <Alert variant="destructive">
                    <AlertCircle className="h-4 w-4" />
                    <AlertDescription>{displayError}</AlertDescription>
                  </Alert>
                )}

                {/* Role Selection */}
                <div className="space-y-2">
                  <Label>I am a</Label>
                  <RadioGroup
                    value={selectedRole}
                    onValueChange={(value) => handleRoleChange(value as 'STUDENT' | 'TRAINER')}
                    className="flex gap-6"
                  >
                    <div className="flex items-center space-x-2">
                      <RadioGroupItem value="STUDENT" id="student" />
                      <Label htmlFor="student" className="cursor-pointer">
                        Student
                      </Label>
                    </div>
                    <div className="flex items-center space-x-2">
                      <RadioGroupItem value="TRAINER" id="trainer" />
                      <Label htmlFor="trainer" className="cursor-pointer">
                        Trainer
                      </Label>
                    </div>
                  </RadioGroup>
                  <input type="hidden" {...register('role')} />
                </div>

                {/* College Selection */}
                <div className="space-y-2">
                  <Label htmlFor="collegeId">College *</Label>
                  <Select
                    onValueChange={(value) => setValue('collegeId', parseInt(value))}
                    disabled={collegesLoading || isLoading}
                  >
                    <SelectTrigger id="collegeId">
                      <SelectValue placeholder="Select your college" />
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
                          No colleges available
                        </SelectItem>
                      )}
                    </SelectContent>
                  </Select>
                  {errors.collegeId && (
                    <p className="text-sm text-destructive">{errors.collegeId.message}</p>
                  )}
                </div>

                {/* Full Name */}
                <div className="space-y-2">
                  <Label htmlFor="fullName">Full Name *</Label>
                  <Input
                    id="fullName"
                    placeholder="John Doe"
                    {...register('fullName')}
                    disabled={isLoading}
                  />
                  {errors.fullName && (
                    <p className="text-sm text-destructive">{errors.fullName.message}</p>
                  )}
                </div>

                {/* Email */}
                <div className="space-y-2">
                  <Label htmlFor="email">Email *</Label>
                  <Input
                    id="email"
                    type="email"
                    placeholder="you@example.com"
                    {...register('email')}
                    disabled={isLoading}
                    autoComplete="email"
                  />
                  {errors.email && (
                    <p className="text-sm text-destructive">{errors.email.message}</p>
                  )}
                </div>

                {/* Password */}
                <div className="space-y-2">
                  <Label htmlFor="password">Password *</Label>
                  <Input
                    id="password"
                    type="password"
                    placeholder="Enter password"
                    {...register('password')}
                    disabled={isLoading}
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
                  <Label htmlFor="confirmPassword">Confirm Password *</Label>
                  <Input
                    id="confirmPassword"
                    type="password"
                    placeholder="Confirm password"
                    {...register('confirmPassword')}
                    disabled={isLoading}
                    autoComplete="new-password"
                  />
                  {errors.confirmPassword && (
                    <p className="text-sm text-destructive">{errors.confirmPassword.message}</p>
                  )}
                </div>

                {/* Student-Specific Fields */}
                {selectedRole === 'STUDENT' && (
                  <>
                    <div className="space-y-2">
                      <Label htmlFor="rollNumber">Roll Number *</Label>
                      <Input
                        id="rollNumber"
                        placeholder="12345"
                        {...register('rollNumber')}
                        disabled={isLoading}
                      />
                      {errors.rollNumber && (
                        <p className="text-sm text-destructive">{errors.rollNumber.message}</p>
                      )}
                    </div>

                    <div className="grid grid-cols-2 gap-4">
                      <div className="space-y-2">
                        <Label htmlFor="degree">Degree</Label>
                        <Input
                          id="degree"
                          placeholder="B.Tech"
                          {...register('degree')}
                          disabled={isLoading}
                        />
                      </div>

                      <div className="space-y-2">
                        <Label htmlFor="branch">Branch</Label>
                        <Input
                          id="branch"
                          placeholder="Computer Science"
                          {...register('branch')}
                          disabled={isLoading}
                        />
                      </div>
                    </div>

                    <div className="space-y-2">
                      <Label htmlFor="year">Year</Label>
                      <Input
                        id="year"
                        type="number"
                        placeholder="3"
                        min="1"
                        max="10"
                        {...register('year', { valueAsNumber: true })}
                        disabled={isLoading}
                      />
                    </div>
                  </>
                )}

                {/* Trainer-Specific Fields */}
                {selectedRole === 'TRAINER' && (
                  <>
                    <div className="space-y-2">
                      <Label htmlFor="department">Department</Label>
                      <Input
                        id="department"
                        placeholder="Computer Science"
                        {...register('department')}
                        disabled={isLoading}
                      />
                    </div>

                    <div className="space-y-2">
                      <Label htmlFor="specialization">Specialization</Label>
                      <Input
                        id="specialization"
                        placeholder="Machine Learning"
                        {...register('specialization')}
                        disabled={isLoading}
                      />
                    </div>

                    <div className="space-y-2">
                      <Label htmlFor="bio">Bio</Label>
                      <Input
                        id="bio"
                        placeholder="Brief introduction about yourself"
                        {...register('bio')}
                        disabled={isLoading}
                      />
                    </div>
                  </>
                )}

                {/* Submit Button */}
                <Button type="submit" className="w-full" disabled={isLoading || collegesLoading}>
                  {isLoading ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      Creating account...
                    </>
                  ) : (
                    'Create Account'
                  )}
                </Button>

                {/* Login Link */}
                <div className="text-center text-sm text-muted-foreground">
                  Already have an account?{' '}
                  <Link to="/login" className="text-primary hover:underline">
                    Sign in
                  </Link>
                </div>
              </form>
            </CardContent>
          </Card>
        </PageWrapper>
      </main>
    </div>
  )
}

