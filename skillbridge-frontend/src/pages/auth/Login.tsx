/**
 * Login Page
 * 
 * Authentication page for System Admin and College Admins
 * Features:
 * - Email + Password form
 * - Form validation (React Hook Form + Zod)
 * - Error handling
 * - Loading states
 * - Redirects based on role after login
 */

import { useState, useEffect } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useAuth } from '@/shared/hooks/useAuth'
import { Button, Input, Label, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/components/ui'
import { Header } from '@/shared/components/layout'
import { PageWrapper } from '@/shared/components/layout'
import { Alert, AlertDescription } from '@/shared/components/ui'
import { AlertCircle } from 'lucide-react'

// Login form schema
const loginSchema = z.object({
  email: z.string().email('Please enter a valid email address'),
  password: z.string().min(1, 'Password is required'),
})

type LoginFormData = z.infer<typeof loginSchema>

export function Login() {
  const navigate = useNavigate()
  const location = useLocation()
  const { login, isLoading, error, isAuthenticated, clearError } = useAuth()
  const [localError, setLocalError] = useState<string | null>(null)

  // Clear errors when component mounts
  useEffect(() => {
    clearError()
    setLocalError(null)
  }, [clearError])
  
  // Note: Removed redirect on isAuthenticated to avoid conflicts
  // AuthContext handles all navigation after login based on user role

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormData>({
    resolver: zodResolver(loginSchema),
    defaultValues: {
      email: '',
      password: '',
    },
  })

  const onSubmit = async (data: LoginFormData) => {
    try {
      setLocalError(null)
      clearError()
      await login(data)
      // Navigation is handled by AuthContext based on role
    } catch (error: any) {
      // Error is handled by AuthContext, but we can show a local message too
      setLocalError(error?.response?.data?.message || 'Login failed. Please try again.')
    }
  }

  const displayError = error || localError

  return (
    <div className="min-h-screen flex flex-col bg-background">
      <Header />
      <main className="flex-1 flex items-center justify-center p-4">
        <PageWrapper maxWidth="md" padding={false}>
          <Card className="w-full max-w-md mx-auto">
            <CardHeader className="space-y-1">
              <CardTitle className="text-2xl font-bold text-center">Sign In</CardTitle>
              <CardDescription className="text-center">
                Enter your credentials to access your account
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
                {/* Error Alert */}
                {displayError && (
                  <Alert variant="destructive">
                    <AlertCircle className="h-4 w-4" />
                    <AlertDescription>{displayError}</AlertDescription>
                  </Alert>
                )}

                {/* Email Field */}
                <div className="space-y-2">
                  <Label htmlFor="email">Email</Label>
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

                {/* Password Field */}
                <div className="space-y-2">
                  <Label htmlFor="password">Password</Label>
                  <Input
                    id="password"
                    type="password"
                    placeholder="Enter your password"
                    {...register('password')}
                    disabled={isLoading}
                    autoComplete="current-password"
                  />
                  {errors.password && (
                    <p className="text-sm text-destructive">{errors.password.message}</p>
                  )}
                </div>

                {/* Forgot Password Link */}
                <div className="flex justify-end">
                  <Link
                    to="/forgot-password"
                    className="text-sm text-primary hover:underline"
                  >
                    Forgot password?
                  </Link>
                </div>

                {/* Submit Button */}
                <Button type="submit" className="w-full" disabled={isLoading}>
                  {isLoading ? 'Signing in...' : 'Sign In'}
                </Button>

                {/* Register Link */}
                <div className="text-center text-sm text-muted-foreground">
                  Don't have an account?{' '}
                  <Link to="/register" className="text-primary hover:underline">
                    Register as Student or Trainer
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

