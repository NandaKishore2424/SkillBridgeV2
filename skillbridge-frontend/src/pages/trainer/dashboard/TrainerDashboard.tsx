/**
 * Trainer Dashboard
 * 
 * Trainer's main dashboard showing:
 * - Statistics (assigned batches, students, pending updates)
 * - Assigned batches list
 * - Quick actions
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
  Badge,
} from '@/shared/components/ui'
import { StatCardSkeleton, ListSkeleton } from '@/shared/components/ui/loading-skeleton'
import {
  getTrainerDashboardStats,
  getTrainerBatches,
  type TrainerBatch,
} from '@/api/trainer'
import {
  BookOpen,
  Users,
  Clock,
  Loader2,
  AlertCircle,
  ArrowRight,
  GraduationCap,
} from 'lucide-react'

interface StatCardProps {
  title: string
  value: number | string
  description?: string
  icon: React.ReactNode
  link?: string
}

function StatCard({ title, value, description, icon, link }: StatCardProps) {
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
              View all <ArrowRight className="ml-1 h-3 w-3" />
            </Link>
          </Button>
        )}
      </CardContent>
    </Card>
  )
}

const STATUS_COLORS: Record<string, 'default' | 'secondary' | 'outline'> = {
  UPCOMING: 'outline',
  OPEN: 'default',
  ACTIVE: 'default',
  COMPLETED: 'secondary',
  CANCELLED: 'secondary',
}

export function TrainerDashboard() {
  const {
    data: stats,
    isLoading: statsLoading,
    error: statsError,
  } = useQuery({
    queryKey: ['trainer', 'dashboard', 'stats'],
    queryFn: getTrainerDashboardStats,
  })

  const {
    data: batches,
    isLoading: batchesLoading,
    error: batchesError,
  } = useQuery({
    queryKey: ['trainer', 'batches'],
    queryFn: getTrainerBatches,
  })

  return (
    <RoleGuard allowedRoles={['TRAINER']}>
      <AuthenticatedLayout>
        <PageWrapper>
          <div className="space-y-6">
            {/* Header */}
            <div>
              <h1 className="text-3xl font-bold tracking-tight">Trainer Dashboard</h1>
              <p className="text-muted-foreground">
                Manage your assigned batches and track student progress
              </p>
            </div>

            {/* Error Alerts */}
            {statsError && (
              <Alert variant="destructive">
                <AlertCircle className="h-4 w-4" />
                <AlertDescription>
                  Failed to load dashboard statistics. Please try again.
                </AlertDescription>
              </Alert>
            )}

            {batchesError && (
              <Alert variant="destructive">
                <AlertCircle className="h-4 w-4" />
                <AlertDescription>
                  Failed to load batches. Please try again.
                </AlertDescription>
              </Alert>
            )}

            {/* Statistics Cards */}
            {statsLoading ? (
              <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
                <StatCardSkeleton />
                <StatCardSkeleton />
                <StatCardSkeleton />
              </div>
            ) : (
              <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
                <StatCard
                  title="Assigned Batches"
                  value={stats?.assignedBatches || 0}
                  description={`${stats?.activeBatches || 0} active`}
                  icon={<BookOpen className="h-4 w-4" />}
                  link="/trainer/batches"
                />
                <StatCard
                  title="Total Students"
                  value={stats?.totalStudents || 0}
                  description="Across all batches"
                  icon={<Users className="h-4 w-4" />}
                />
                <StatCard
                  title="Pending Updates"
                  value={stats?.pendingProgressUpdates || 0}
                  description="Progress updates needed"
                  icon={<Clock className="h-4 w-4" />}
                />
              </div>
            )}

            {/* Assigned Batches */}
            <Card>
              <CardHeader>
                <CardTitle>My Assigned Batches</CardTitle>
                <CardDescription>
                  Batches you are assigned to teach
                </CardDescription>
              </CardHeader>
              <CardContent>
                {batchesLoading ? (
                  <ListSkeleton items={3} />
                ) : batches && batches.length > 0 ? (
                  <div className="space-y-4">
                    {batches.map((batch) => (
                      <Card key={batch.id} className="hover:shadow-md transition-shadow">
                        <CardContent className="pt-6">
                          <div className="flex items-start justify-between">
                            <div className="flex-1">
                              <div className="flex items-center gap-3 mb-2">
                                <h3 className="text-lg font-semibold">{batch.name}</h3>
                                <Badge variant={STATUS_COLORS[batch.status]}>
                                  {batch.status}
                                </Badge>
                              </div>
                              {batch.description && (
                                <p className="text-sm text-muted-foreground mb-3">
                                  {batch.description}
                                </p>
                              )}
                              <div className="flex items-center gap-4 text-sm text-muted-foreground">
                                <div className="flex items-center gap-1">
                                  <GraduationCap className="h-4 w-4" />
                                  {batch.enrolledCount || 0} students
                                </div>
                                {batch.syllabus && (
                                  <div className="flex items-center gap-1">
                                    <BookOpen className="h-4 w-4" />
                                    {batch.syllabus.topicCount} topics
                                  </div>
                                )}
                                {batch.startDate && batch.endDate && (
                                  <div>
                                    {new Date(batch.startDate).toLocaleDateString()} -{' '}
                                    {new Date(batch.endDate).toLocaleDateString()}
                                  </div>
                                )}
                              </div>
                            </div>
                            <Button asChild variant="outline">
                              <Link to={`/trainer/batches/${batch.id}`}>
                                View Details
                                <ArrowRight className="ml-2 h-4 w-4" />
                              </Link>
                            </Button>
                          </div>
                        </CardContent>
                      </Card>
                    ))}
                  </div>
                ) : (
                  <div className="text-center py-12">
                    <BookOpen className="mx-auto h-12 w-12 text-muted-foreground mb-4" />
                    <h3 className="text-lg font-semibold mb-2">No batches assigned</h3>
                    <p className="text-muted-foreground">
                      You will see batches here once they are assigned to you
                    </p>
                  </div>
                )}
              </CardContent>
            </Card>
          </div>
        </PageWrapper>
      </AuthenticatedLayout>
    </RoleGuard>
  )
}

