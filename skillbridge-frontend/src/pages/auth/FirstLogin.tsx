/**
 * First Login Page
 *
 * Where an account exchanges the temporary password it was created with for a
 * real one.
 *
 * The backend has set `mustChangePassword` on every provisioned account since
 * that column was introduced, and `POST /auth/first-login` has existed to clear
 * it — but nothing enforced the flag and no screen ever called the endpoint. So
 * accounts sailed past this step. Once the backend started enforcing it, those
 * accounts could log in and then reach nothing: every panel rendered "Failed to
 * load" from a 403 they had no way to resolve. This page is that missing step.
 */

import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import * as authAPI from '@/api/auth'
import { Button, Input, Label, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/components/ui'
import { Header } from '@/shared/components/layout'
import { PageWrapper } from '@/shared/components/layout'
import { Alert, AlertDescription } from '@/shared/components/ui'
import { AlertCircle } from 'lucide-react'

const firstLoginSchema = z
  .object({
    email: z.string().email('Please enter a valid email address'),
    temporaryPassword: z.string().min(1, 'Your temporary password is required'),
    // Matches the backend: HS256-signed sessions are only as good as the
    // password behind them, and these accounts were provisioned in bulk.
    newPassword: z.string().min(8, 'Use at least 8 characters'),
    confirmPassword: z.string().min(1, 'Please confirm your new password'),
  })
  .refine((data) => data.newPassword === data.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  })
  .refine((data) => data.newPassword !== data.temporaryPassword, {
    message: 'Your new password must be different from the temporary one',
    path: ['newPassword'],
  })

type FirstLoginFormData = z.infer<typeof firstLoginSchema>

export function FirstLogin() {
  const navigate = useNavigate()
  const location = useLocation()
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  // Prefilled when we arrived here straight from a login that came back with
  // mustChangePassword; typed by hand if someone opens the page directly.
  const emailFromLogin = (location.state as { email?: string } | null)?.email ?? ''

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FirstLoginFormData>({
    resolver: zodResolver(firstLoginSchema),
    defaultValues: { email: emailFromLogin },
  })

  const onSubmit = async (data: FirstLoginFormData) => {
    setSubmitting(true)
    setError(null)
    try {
      await authAPI.firstLogin({
        email: data.email,
        temporaryPassword: data.temporaryPassword,
        newPassword: data.newPassword,
      })

      // The endpoint returns a fresh token pair, but the tokens held in memory
      // here are the pre-change ones. Rather than reconcile that, send the user
      // back through login once — it is one extra step, on an account that has
      // just proved it knows both passwords.
      navigate('/login', {
        replace: true,
        state: { message: 'Password updated. Please sign in with your new password.' },
      })
    } catch (err: any) {
      setError(
        err?.response?.data?.message ??
          'Could not update your password. Check your temporary password and try again.'
      )
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <Header />
      <PageWrapper>
        <div className="flex items-center justify-center py-12">
          <Card className="w-full max-w-md">
            <CardHeader>
              <CardTitle>Set your password</CardTitle>
              <CardDescription>
                Your account was created with a temporary password. Choose a new one to continue.
              </CardDescription>
            </CardHeader>
            <CardContent>
              {error && (
                <Alert variant="destructive" className="mb-4">
                  <AlertCircle className="h-4 w-4" />
                  <AlertDescription>{error}</AlertDescription>
                </Alert>
              )}

              <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
                <div className="space-y-2">
                  <Label htmlFor="email">Email</Label>
                  <Input id="email" type="email" placeholder="you@example.com" {...register('email')} />
                  {errors.email && <p className="text-sm text-red-500">{errors.email.message}</p>}
                </div>

                <div className="space-y-2">
                  <Label htmlFor="temporaryPassword">Temporary password</Label>
                  <Input
                    id="temporaryPassword"
                    type="password"
                    placeholder="The password you were sent"
                    {...register('temporaryPassword')}
                  />
                  {errors.temporaryPassword && (
                    <p className="text-sm text-red-500">{errors.temporaryPassword.message}</p>
                  )}
                </div>

                <div className="space-y-2">
                  <Label htmlFor="newPassword">New password</Label>
                  <Input id="newPassword" type="password" placeholder="At least 8 characters" {...register('newPassword')} />
                  {errors.newPassword && <p className="text-sm text-red-500">{errors.newPassword.message}</p>}
                </div>

                <div className="space-y-2">
                  <Label htmlFor="confirmPassword">Confirm new password</Label>
                  <Input id="confirmPassword" type="password" placeholder="Repeat your new password" {...register('confirmPassword')} />
                  {errors.confirmPassword && (
                    <p className="text-sm text-red-500">{errors.confirmPassword.message}</p>
                  )}
                </div>

                <Button type="submit" className="w-full" disabled={submitting}>
                  {submitting ? 'Updating…' : 'Set password'}
                </Button>
              </form>
            </CardContent>
          </Card>
        </div>
      </PageWrapper>
    </>
  )
}

export default FirstLogin
