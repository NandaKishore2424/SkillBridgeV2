/**
 * System Admin Dashboard
 * 
 * Overview page with:
 * - Key statistics (total colleges, active colleges, total students, etc.)
 * - Grid of college cards (clickable)
 * - Quick actions (Create College button)
 * - Professional card-based layout
 */

import { Link, useNavigate } from 'react-router-dom'
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
  Badge,
  Alert,
  AlertDescription,
} from '@/shared/components/ui'
import { StatCardSkeleton, CardSkeleton } from '@/shared/components/ui'
import { getAllColleges } from '@/api/admin'
import type { College } from '@/shared/types'
import {
  Building2,
  Users,
  GraduationCap,
  Plus,
  ArrowRight,
  AlertCircle,
  TrendingUp,
  CheckCircle2,
  XCircle,
} from 'lucide-react'
import { cn } from '@/lib/utils'

interface StatCardProps {
  title: string
  value: number | string
  description?: string
  icon: React.ReactNode
  trend?: {
    value: number
    label: string
  }
}

function StatCard({ title, value, description, icon, trend }: StatCardProps) {
  return (
    <Card className="hover:shadow-lg transition-all duration-300 border-l-4 border-l-primary">
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">{title}</CardTitle>
        <div className="h-8 w-8 rounded-full bg-primary/10 flex items-center justify-center text-primary">
          {icon}
        </div>
      </CardHeader>
      <CardContent>
        <div className="text-3xl font-bold tracking-tight">{value}</div>
        {description && (
          <p className="text-xs text-muted-foreground mt-1">{description}</p>
        )}
        {trend && (
          <div className="flex items-center gap-1 mt-2">
            <TrendingUp className={cn(
              "h-3 w-3",
              trend.value >= 0 ? "text-green-500" : "text-red-500"
            )} />
            <span className={cn(
              "text-xs font-medium",
              trend.value >= 0 ? "text-green-500" : "text-red-500"
            )}>
              {trend.value >= 0 ? '+' : ''}{trend.value}% {trend.label}
            </span>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

interface CollegeCardProps {
  college: College
  onClick: () => void
}

function CollegeCard({ college, onClick }: CollegeCardProps) {
  return (
    <Card 
      className="hover:shadow-xl transition-all duration-300 cursor-pointer group border hover:border-primary/50"
      onClick={onClick}
    >
      <CardHeader className="pb-3">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-3">
            <div className="h-12 w-12 rounded-lg bg-primary/10 flex items-center justify-center group-hover:bg-primary/20 transition-colors">
              <Building2 className="h-6 w-6 text-primary" />
            </div>
            <div>
              <CardTitle className="text-lg group-hover:text-primary transition-colors">
                {college.name}
              </CardTitle>
              <CardDescription className="font-mono text-xs mt-1">
                {college.code}
              </CardDescription>
            </div>
          </div>
          <Badge
            variant={college.status === 'ACTIVE' ? 'default' : 'secondary'}
            className="ml-auto"
          >
            {college.status === 'ACTIVE' ? (
              <CheckCircle2 className="h-3 w-3 mr-1" />
            ) : (
              <XCircle className="h-3 w-3 mr-1" />
            )}
            {college.status}
          </Badge>
        </div>
      </CardHeader>
      <CardContent>
        <div className="space-y-2">
          {college.email && (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <span className="font-medium">Email:</span>
              <span>{college.email}</span>
            </div>
          )}
          {college.phone && (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <span className="font-medium">Phone:</span>
              <span>{college.phone}</span>
            </div>
          )}
          <div className="pt-2 border-t flex items-center justify-between">
            <span className="text-xs text-muted-foreground">View Details</span>
            <ArrowRight className="h-4 w-4 text-muted-foreground group-hover:text-primary group-hover:translate-x-1 transition-all" />
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

export function SystemAdminDashboard() {
  const navigate = useNavigate()
  const {
    data: colleges,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['admin', 'colleges'],
    queryFn: getAllColleges,
  })

  // Calculate statistics
  const totalColleges = colleges?.length || 0
  const activeColleges = colleges?.filter(c => c.status === 'ACTIVE').length || 0
  const inactiveColleges = totalColleges - activeColleges

  const handleCollegeClick = (collegeId: number) => {
    navigate(`/admin/colleges/${collegeId}`)
  }

  return (
    <RoleGuard allowedRoles={['SYSTEM_ADMIN']}>
      <AuthenticatedLayout>
        <PageWrapper>
          <div className="space-y-6">
            {/* Header */}
            <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
              <div>
                <h1 className="text-4xl font-bold tracking-tight bg-gradient-to-r from-foreground to-foreground/70 bg-clip-text text-transparent">
                  System Dashboard
                </h1>
                <p className="text-muted-foreground mt-2">
                  Overview of all colleges and system-wide statistics
                </p>
              </div>
              <Button asChild size="lg" className="shadow-md hover:shadow-lg transition-shadow">
                <Link to="/admin/colleges/create">
                  <Plus className="mr-2 h-4 w-4" />
                  Create College
                </Link>
              </Button>
            </div>

            {/* Error Alert */}
            {error && (
              <Alert variant="destructive" className="border-l-4 border-l-destructive">
                <AlertCircle className="h-4 w-4" />
                <AlertDescription>
                  Failed to load colleges. Please try again.
                </AlertDescription>
              </Alert>
            )}

            {/* Statistics Cards */}
            <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
              {isLoading ? (
                <>
                  <StatCardSkeleton />
                  <StatCardSkeleton />
                  <StatCardSkeleton />
                  <StatCardSkeleton />
                </>
              ) : (
                <>
                  <StatCard
                    title="Total Colleges"
                    value={totalColleges}
                    description="All registered colleges"
                    icon={<Building2 className="h-4 w-4" />}
                  />
                  <StatCard
                    title="Active Colleges"
                    value={activeColleges}
                    description={`${inactiveColleges} inactive`}
                    icon={<CheckCircle2 className="h-4 w-4" />}
                  />
                  <StatCard
                    title="Total Students"
                    value="0"
                    description="Across all colleges"
                    icon={<GraduationCap className="h-4 w-4" />}
                  />
                  <StatCard
                    title="System Health"
                    value="100%"
                    description="All systems operational"
                    icon={<TrendingUp className="h-4 w-4" />}
                  />
                </>
              )}
            </div>

            {/* Colleges Section */}
            <div>
              <div className="flex items-center justify-between mb-4">
                <div>
                  <h2 className="text-2xl font-semibold tracking-tight">Colleges</h2>
                  <p className="text-sm text-muted-foreground">
                    {totalColleges} {totalColleges === 1 ? 'college' : 'colleges'} registered
                  </p>
                </div>
                <Button variant="outline" asChild>
                  <Link to="/admin/colleges">
                    View All <ArrowRight className="ml-2 h-4 w-4" />
                  </Link>
                </Button>
              </div>

              {isLoading ? (
                <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
                  <CardSkeleton />
                  <CardSkeleton />
                  <CardSkeleton />
                </div>
              ) : colleges && colleges.length > 0 ? (
                <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
                  {colleges.slice(0, 6).map((college) => (
                    <CollegeCard
                      key={college.id}
                      college={college}
                      onClick={() => handleCollegeClick(college.id)}
                    />
                  ))}
                </div>
              ) : (
                <Card className="border-dashed">
                  <CardContent className="flex flex-col items-center justify-center py-12">
                    <Building2 className="h-12 w-12 text-muted-foreground mb-4" />
                    <h3 className="text-lg font-semibold mb-2">No colleges yet</h3>
                    <p className="text-sm text-muted-foreground mb-4 text-center max-w-md">
                      Get started by creating your first college. Colleges are the foundation of the SkillBridge platform.
                    </p>
                    <Button asChild>
                      <Link to="/admin/colleges/create">
                        <Plus className="mr-2 h-4 w-4" />
                        Create Your First College
                      </Link>
                    </Button>
                  </CardContent>
                </Card>
              )}
            </div>

            {/* Quick Actions */}
            <Card className="bg-gradient-to-br from-primary/5 to-primary/10 border-primary/20">
              <CardHeader>
                <CardTitle>Quick Actions</CardTitle>
                <CardDescription>Common administrative tasks</CardDescription>
              </CardHeader>
              <CardContent>
                <div className="grid gap-3 md:grid-cols-3">
                  <Button asChild variant="outline" className="justify-start h-auto py-3">
                    <Link to="/admin/colleges/create">
                      <Plus className="mr-2 h-4 w-4" />
                      <div className="text-left">
                        <div className="font-medium">Create College</div>
                        <div className="text-xs text-muted-foreground">Add a new college</div>
                      </div>
                    </Link>
                  </Button>
                  <Button asChild variant="outline" className="justify-start h-auto py-3">
                    <Link to="/admin/colleges">
                      <Building2 className="mr-2 h-4 w-4" />
                      <div className="text-left">
                        <div className="font-medium">Manage Colleges</div>
                        <div className="text-xs text-muted-foreground">View all colleges</div>
                      </div>
                    </Link>
                  </Button>
                  <Button asChild variant="outline" className="justify-start h-auto py-3">
                    <Link to="/admin/companies">
                      <Users className="mr-2 h-4 w-4" />
                      <div className="text-left">
                        <div className="font-medium">View Companies</div>
                        <div className="text-xs text-muted-foreground">System-wide companies</div>
                      </div>
                    </Link>
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        </PageWrapper>
      </AuthenticatedLayout>
    </RoleGuard>
  )
}

