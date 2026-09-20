import { ArrowLeft, Compass } from 'lucide-react'
import { Link, useLocation } from 'react-router-dom'

import { Button } from '@/shared/components/ui/button'
import { Footer, Header } from '@/shared/components/layout'
import { dashboardPathFor } from '@/shared/auth/dashboardPath'
import { useAuth } from '@/shared/hooks/useAuth'

/**
 * What an unknown URL gets, instead of a silent trip to the landing page.
 *
 * The catch-all used to be `<Navigate to="/" replace />`. That is the worst
 * possible answer to a wrong address: the person clicks something, lands on the
 * marketing page, and concludes they have been signed out. It also hid four
 * genuinely broken links in this application for months -- there was nothing to
 * see, because being sent home looks like a page that chose to send you home.
 * `shared/rules/routes.test.ts` now stops a fifth appearing; this is what
 * happens when somebody types a URL by hand, or follows a stale bookmark.
 *
 * The path is shown back, because "that address does not exist" is only useful
 * if you can see which address was tried.
 */
export function NotFound() {
  const { user, logout } = useAuth()
  const { pathname } = useLocation()
  const home = user ? dashboardPathFor(user.role) : '/'

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <Header
        user={user ? { email: user.email, role: user.role } : undefined}
        onLogout={logout}
      />

      <main className="container flex flex-1 items-center justify-center py-20">
        <div className="max-w-md text-center">
          <span className="mx-auto mb-6 flex h-14 w-14 items-center justify-center rounded-2xl bg-primary/10 text-primary">
            <Compass className="h-7 w-7" />
          </span>
          <p className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">
            404
          </p>
          <h1 className="mt-2 text-3xl font-bold tracking-tight">This page does not exist</h1>
          <p className="mt-4 text-muted-foreground">
            Nothing is served at{' '}
            <code className="break-all rounded bg-muted px-1.5 py-0.5 text-sm">{pathname}</code>.
            The link may be out of date.
          </p>
          <Button asChild className="mt-8">
            <Link to={home}>
              <ArrowLeft className="mr-2 h-4 w-4" />
              {user ? 'Back to your dashboard' : 'Back to the home page'}
            </Link>
          </Button>
        </div>
      </main>

      <Footer />
    </div>
  )
}
