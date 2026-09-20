import { useQuery } from '@tanstack/react-query'
import {
  ArrowRight,
  BookOpen,
  Briefcase,
  GraduationCap,
  Plus,
  Upload,
  Users,
} from 'lucide-react'
import { Link } from 'react-router-dom'

import { getDashboardStats } from '@/api/college-admin'
import { RoleGuard } from '@/shared/components/auth'
import { AuthenticatedLayout, PageWrapper } from '@/shared/components/layout'
import { ErrorState, PageHeader, StatCard } from '@/shared/components/page'
import {
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/shared/components/ui'
import { StatCardSkeleton } from '@/shared/components/ui/loading-skeleton'

/**
 * The college admin's landing page.
 *
 * Two things were removed rather than restyled.
 *
 * **"Analytics -- Detailed analytics and reporting features coming soon"**, with
 * a permanently disabled button. A promise on the first screen of the product
 * is worse than a gap: it is the only thing on the page a visitor cannot try,
 * and it says the rest might be aspirational too.
 *
 * **A "Recent Activity" card** reading `stats.recentActivity`, which the backend
 * has never sent -- `DashboardController` returns five counts and nothing else,
 * and the string "recentActivity" appears nowhere in the Java. The field was
 * declared optional in the TypeScript, so the block silently never rendered and
 * nothing ever failed. `DashboardStats` no longer declares it either.
 *
 * A third card, "Management", listed Batches / Students / Trainers / Companies
 * -- the sidebar, again, in the middle of the page. The counts already link
 * there.
 */

const QUICK_ACTIONS = [
  { to: '/admin/batches/create', icon: Plus, label: 'Create a batch', primary: true },
  { to: '/admin/students/upload', icon: Upload, label: 'Bulk upload students' },
  { to: '/admin/trainers/upload', icon: Upload, label: 'Bulk upload trainers' },
  { to: '/admin/trainers/create', icon: Plus, label: 'Add a trainer' },
  { to: '/admin/companies/create', icon: Plus, label: 'Add a company' },
]

export function Dashboard() {
  const {
    data: stats,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['admin', 'dashboard', 'stats'],
    queryFn: getDashboardStats,
  })

  return (
    <RoleGuard allowedRoles={['COLLEGE_ADMIN']}>
      <AuthenticatedLayout>
        <PageWrapper>
          <div className="space-y-8">
            <PageHeader
              title="Dashboard"
              description="Your college's training programme at a glance."
              actions={
                <Button asChild>
                  <Link to="/admin/batches/create">
                    <Plus className="mr-2 h-4 w-4" />
                    Create a batch
                  </Link>
                </Button>
              }
            />

            {error && <ErrorState error={error} title="Could not load the figures" />}

            {/* A labelled region: a screen-reader user can jump to the
                summary, and a test can assert on a figure without matching the
                same word in the sidebar. */}
            <section aria-label="At a glance" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              {isLoading ? (
                [0, 1, 2, 3].map((index) => <StatCardSkeleton key={index} />)
              ) : (
                <>
                <StatCardLink to="/admin/batches">
                  <StatCard
                    label="Batches"
                    value={stats?.totalBatches ?? 0}
                    hint={`${stats?.activeBatches ?? 0} running now`}
                    icon={BookOpen}
                  />
                </StatCardLink>
                <StatCardLink to="/admin/students">
                  <StatCard
                    label="Students"
                    value={stats?.totalStudents ?? 0}
                    hint="On the college roll"
                    icon={GraduationCap}
                  />
                </StatCardLink>
                <StatCardLink to="/admin/trainers">
                  <StatCard
                    label="Trainers"
                    value={stats?.totalTrainers ?? 0}
                    hint="Teaching your batches"
                    icon={Users}
                  />
                </StatCardLink>
                <StatCardLink to="/admin/companies">
                  <StatCard
                    label="Companies"
                    value={stats?.totalCompanies ?? 0}
                    hint="Linked to batches"
                    icon={Briefcase}
                  />
                </StatCardLink>
                </>
              )}
            </section>

            <Card>
              <CardHeader>
                <CardTitle>Get something done</CardTitle>
                <CardDescription>
                  The five things a college admin does most. Everything else is in the
                  sidebar.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  {QUICK_ACTIONS.map(({ to, icon: Icon, label, primary }) => (
                    <Button
                      key={to}
                      asChild
                      variant={primary ? 'default' : 'outline'}
                      className="h-auto justify-start py-3"
                    >
                      <Link to={to}>
                        <Icon className="mr-2 h-4 w-4" />
                        {label}
                      </Link>
                    </Button>
                  ))}
                </div>
              </CardContent>
            </Card>
          </div>
        </PageWrapper>
      </AuthenticatedLayout>
    </RoleGuard>
  )
}

/**
 * Makes a whole stat card a link.
 *
 * The card used to carry a "View all →" button in its corner, which is a small
 * target next to a large inert rectangle that looks clickable anyway.
 */
function StatCardLink({ to, children }: { to: string; children: React.ReactNode }) {
  return (
    <Link
      to={to}
      className="group rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
    >
      <div className="relative">
        {children}
        <ArrowRight className="absolute bottom-4 right-4 h-4 w-4 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100" />
      </div>
    </Link>
  )
}
