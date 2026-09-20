import { zodResolver } from '@hookform/resolvers/zod'
import { AlertCircle, ArrowLeft, Loader2, Sparkles } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router-dom'
import { z } from 'zod'

import { apiErrorMessage } from '@/lib/apiError'
import {
  Alert,
  AlertDescription,
  Button,
  Input,
  Label,
} from '@/shared/components/ui'
import { ThemeToggle } from '@/shared/theme'
import { useAuth } from '@/shared/hooks/useAuth'

import { FACTS } from '../landing/facts'

/**
 * Sign in.
 *
 * Two columns on a wide screen, one on a phone. The right-hand panel is not
 * decoration: signing in is the only page an unknown visitor reaches from an
 * emailed invitation, and it is the one place to say what this is before asking
 * for a password.
 *
 * Navigation after a successful login is `AuthContext`'s, not this page's --
 * including the detour to `/first-login` for an account that still holds a
 * temporary password. Doing it in both places is how a redirect ends up racing
 * itself.
 */

const loginSchema = z.object({
  email: z.string().email('Enter a valid email address'),
  password: z.string().min(1, 'Enter your password'),
})

type LoginFormData = z.infer<typeof loginSchema>

export function Login() {
  const { login, error, clearError } = useAuth()
  const [localError, setLocalError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormData>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: '', password: '' },
  })

  const onSubmit = async (data: LoginFormData) => {
    try {
      setLocalError(null)
      clearError()
      await login(data)
    } catch (err) {
      // The server's own words. Login failures are uniform by design -- the
      // backend never says whether it was the address or the password -- but a
      // rate-limit or an expired invitation says something specific and useful.
      setLocalError(apiErrorMessage(err, 'Could not sign you in. Try again.'))
    }
  }

  const displayError = localError ?? error

  /*
    `isSubmitting`, not `useAuth().isLoading`.

    `isLoading` is one flag for two things: a login in flight, and the session
    restore `AuthProvider` runs on mount. Reading it here meant that on every
    single page load -- including for a visitor with no session at all -- the
    email and password fields were disabled and the button read "Signing in..."
    until a refresh request that was always going to 401 came back. The form
    only needs to lock itself while *this* form is submitting.
  */
  const busy = isSubmitting

  return (
    <div className="grid min-h-screen lg:grid-cols-2">
      {/* ------------------------------------------------------------ form */}
      <div className="flex flex-col">
        <div className="flex items-center justify-between p-4 sm:p-6">
          <Link
            to="/"
            className="inline-flex items-center gap-2 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
          >
            <ArrowLeft className="h-4 w-4" />
            Back
          </Link>
          <ThemeToggle />
        </div>

        <main className="flex flex-1 items-center justify-center px-4 pb-16 sm:px-6">
          <div className="w-full max-w-sm">
            <Link to="/" className="mb-8 flex items-center gap-2">
              <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary font-bold text-primary-foreground">
                SB
              </span>
              <span className="text-lg font-bold tracking-tight">SkillBridge</span>
            </Link>

            <h1 className="text-2xl font-bold tracking-tight">Sign in</h1>
            <p className="mt-2 text-sm text-muted-foreground">
              Use the address your college administrator set your account up with.
            </p>

            <form onSubmit={handleSubmit(onSubmit)} className="mt-8 space-y-5" noValidate>
              {displayError && (
                <Alert variant="destructive">
                  <AlertCircle className="h-4 w-4" />
                  <AlertDescription>{displayError}</AlertDescription>
                </Alert>
              )}

              <div className="space-y-2">
                <Label htmlFor="email">Email</Label>
                <Input
                  id="email"
                  type="email"
                  placeholder="you@college.edu"
                  autoComplete="email"
                  autoFocus
                  disabled={busy}
                  aria-invalid={errors.email ? true : undefined}
                  aria-describedby={errors.email ? 'email-error' : undefined}
                  {...register('email')}
                />
                {errors.email && (
                  <p id="email-error" className="text-sm text-destructive">
                    {errors.email.message}
                  </p>
                )}
              </div>

              <div className="space-y-2">
                <Label htmlFor="password">Password</Label>
                <Input
                  id="password"
                  type="password"
                  placeholder="Your password"
                  autoComplete="current-password"
                  disabled={busy}
                  aria-invalid={errors.password ? true : undefined}
                  aria-describedby={errors.password ? 'password-error' : undefined}
                  {...register('password')}
                />
                {errors.password && (
                  <p id="password-error" className="text-sm text-destructive">
                    {errors.password.message}
                  </p>
                )}
              </div>

              {/*
                "Forgot password?" removed 2026-09-06. There is no reset flow:
                AuthController exposes login, refresh, change-password,
                first-login and logout, and nothing else. `EmailService` has a
                `sendPasswordResetEmail` that no endpoint calls. The link sent
                people to a dead route. Restore it when the reset flow exists.
              */}

              <Button type="submit" className="w-full" size="lg" disabled={busy}>
                {busy && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                {busy ? 'Signing in…' : 'Sign in'}
              </Button>
            </form>

            <p className="mt-8 text-center text-sm text-muted-foreground">
              No account? SkillBridge is invite-only &mdash; ask your college administrator.
            </p>
          </div>
        </main>
      </div>

      {/* ------------------------------------------------------------ panel */}
      <aside
        className="relative hidden overflow-hidden border-l bg-muted/40 lg:block"
        aria-label="About SkillBridge"
      >
        <div className="pointer-events-none absolute inset-0 bg-brand-wash" aria-hidden="true" />
        <div
          className="pointer-events-none absolute inset-0 bg-grid opacity-40 [mask-image:radial-gradient(70%_60%_at_50%_40%,black,transparent)]"
          aria-hidden="true"
        />
        <div className="relative flex h-full flex-col justify-center p-12 xl:p-16">
          <span className="mb-6 inline-flex w-fit items-center gap-2 rounded-full bg-accent px-3 py-1 text-xs font-semibold text-accent-foreground">
            <Sparkles className="h-3.5 w-3.5" />
            Skill-gap analysis
          </span>
          <p className="max-w-md text-2xl font-semibold leading-snug tracking-tight">
            Your skills, measured against{' '}
            {FACTS.corpusSize.toLocaleString()} real job descriptions &mdash; not against a
            checklist somebody wrote once.
          </p>
          <p className="mt-6 max-w-md text-muted-foreground">
            Sign in to see which roles you are closest to, and which skill is worth picking
            up next.
          </p>
        </div>
      </aside>
    </div>
  )
}
