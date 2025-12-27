/**
 * College Admin Dashboard
 * 
 * Overview page with:
 * - Key statistics (batches, students, trainers, companies)
 * - Quick actions
 * - Recent activity
 * - Quick links to main sections
 */

import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { AuthenticatedLayout } from '@/shared/components/layout'
import { PageWrapper } from '@/shared/components/layout'
import { RoleGuard } from '@/shared/components/auth'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Button,
  Alert,
  AlertDescription,
} from '@/shared/components/ui'
import { StatCardSkeleton } from '@/shared/components/ui/loading-skeleton'
import { getDashboardStats } from '@/api/college-admin'
import {
  Building2,
  Users,
  GraduationCap,
  Briefcase,
  Plus,
  ArrowRight,
  Loader2,
  AlertCircle,
  BookOpen,
  TrendingUp,
  Upload,
} from 'lucide-react'

interface StatCardProps {
  title: string
  value: number | string
  description?: string
  icon: React.ReactNode
  link?: string
  linkText?: string
}

function StatCard({ title, value, description, icon, link, linkText }: StatCardProps) {
  return (
    <Card className="hover:shadow-md transition-shadow">
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
        <CardTitle className="text-sm font-medium">{title}</CardTitle>
        <div className="text-muted-foreground">{icon}</div>
      </CardHeader>
      <CardContent>
        <div className="text-2xl font-bold">{value}</div>
        {description && (
          <p className="text-xs text-muted-foreground mt-1">{description}</p>
        )}
        {link && (
          <Button variant="link" className="p-0 h-auto mt-2" asChild>
            <Link to={link}>
              {linkText || 'View all'} <ArrowRight className="ml-1 h-3 w-3" />
            </Link>
          </Button>
        )}
      </CardContent>
    </Card>
  )
}

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
          <div className="space-y-6">
            {/* Header */}
            <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
              <div>
                <h1 className="text-3xl font-bold tracking-tight">Dashboard</h1>
                <p className="text-muted-foreground">
                  Overview of your college's training activities
                </p>
              </div>
            </div>

            {/* Error Alert */}
            {error && (
              <Alert variant="destructive">
                <AlertCircle className="h-4 w-4" />
                <AlertDescription>
                  Failed to load dashboard statistics. Please try again.
                </AlertDescription>
              </Alert>
            )}

            {/* Statistics Cards */}
            {isLoading ? (
              <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
                <StatCardSkeleton />
                <StatCardSkeleton />
                <StatCardSkeleton />
                <StatCardSkeleton />
              </div>
            ) : (
              <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
                <StatCard
                  title="Total Batches"
                  value={stats?.totalBatches || 0}
                  description={`${stats?.activeBatches || 0} active`}
                  icon={<BookOpen className="h-4 w-4" />}
                  link="/admin/batches"
                  linkText="Manage batches"
                />
                <StatCard
                  title="Total Students"
                  value={stats?.totalStudents || 0}
                  description="Enrolled students"
                  icon={<GraduationCap className="h-4 w-4" />}
                  link="/admin/students"
                  linkText="View students"
                />
                <StatCard
                  title="Total Trainers"
                  value={stats?.totalTrainers || 0}
                  description="Active trainers"
                  icon={<Users className="h-4 w-4" />}
                  link="/admin/trainers"
                  linkText="Manage trainers"
                />
                <StatCard
                  title="Total Companies"
                  value={stats?.totalCompanies || 0}
                  description="Linked companies"
                  icon={<Briefcase className="h-4 w-4" />}
                  link="/admin/companies"
                  linkText="View companies"
                />
              </div>
            )}

            {/* Quick Actions */}
            <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
              <Card>
                <CardHeader>
                  <CardTitle>Quick Actions</CardTitle>
                  <CardDescription>Common administrative tasks</CardDescription>
                </CardHeader>
                <CardContent className="space-y-2">
                  <Button asChild className="w-full justify-start">
                    <Link to="/admin/batches/create">
                      <Plus className="mr-2 h-4 w-4" />
                      Create Batch
                    </Link>
                  </Button>
                  <Button asChild variant="outline" className="w-full justify-start">
                    <Link to="/admin/trainers/create">
                      <Plus className="mr-2 h-4 w-4" />
                      Add Trainer
                    </Link>
                  </Button>
                  <Button asChild variant="outline" className="w-full justify-start">
                    <Link to="/admin/companies/create">
                      <Plus className="mr-2 h-4 w-4" />
                      Add Company
                    </Link>
                  </Button>
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle>Bulk Operations</CardTitle>
                  <CardDescription>Import multiple users at once</CardDescription>
                </CardHeader>
                <CardContent className="space-y-2">
                  <Button asChild variant="outline" className="w-full justify-start">
                    <Link to="/admin/students/upload">
                      <Upload className="mr-2 h-4 w-4" />
                      Bulk Upload Students
                    </Link>
                  </Button>
                  <Button asChild variant="outline" className="w-full justify-start">
                    <Link to="/admin/trainers/upload">
                      <Upload className="mr-2 h-4 w-4" />
                      Bulk Upload Trainers
                    </Link>
                  </Button>
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle>Management</CardTitle>
                  <CardDescription>Manage your college resources</CardDescription>
                </CardHeader>
                <CardContent className="space-y-2">
                  <Button asChild variant="outline" className="w-full justify-start">
                    <Link to="/admin/batches">
                      <BookOpen className="mr-2 h-4 w-4" />
                      Batches
                    </Link>
                  </Button>
                  <Button asChild variant="outline" className="w-full justify-start">
                    <Link to="/admin/students">
                      <GraduationCap className="mr-2 h-4 w-4" />
                      Students
                    </Link>
                  </Button>
                  <Button asChild variant="outline" className="w-full justify-start">
                    <Link to="/admin/trainers">
                      <Users className="mr-2 h-4 w-4" />
                      Trainers
                    </Link>
                  </Button>
                  <Button asChild variant="outline" className="w-full justify-start">
                    <Link to="/admin/companies">
                      <Briefcase className="mr-2 h-4 w-4" />
                      Companies
                    </Link>
                  </Button>
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle>Analytics</CardTitle>
                  <CardDescription>View reports and insights</CardDescription>
                </CardHeader>
                <CardContent>
                  <p className="text-sm text-muted-foreground mb-4">
                    Detailed analytics and reporting features coming soon.
                  </p>
                  <Button variant="outline" className="w-full" disabled>
                    <TrendingUp className="mr-2 h-4 w-4" />
                    View Reports
                  </Button>
                </CardContent>
              </Card>
            </div>

            {/* Recent Activity (if available) */}
            {stats?.recentActivity && stats.recentActivity.length > 0 && (
              <Card>
                <CardHeader>
                  <CardTitle>Recent Activity</CardTitle>
                  <CardDescription>Latest updates in your college</CardDescription>
                </CardHeader>
                <CardContent>
                  <div className="space-y-2">
                    {stats.recentActivity.map((activity, index) => (
                      <div
                        key={index}
                        className="flex items-start gap-3 p-2 rounded-md hover:bg-muted/50"
                      >
                        <div className="flex-1">
                          <p className="text-sm font-medium">{activity.message}</p>
                          <p className="text-xs text-muted-foreground">
                            {new Date(activity.timestamp).toLocaleString()}
                          </p>
                        </div>
                      </div>
                    ))}
                  </div>
                </CardContent>
              </Card>
            )}
          </div>
        </PageWrapper>
      </AuthenticatedLayout>
    </RoleGuard>
  )
}

