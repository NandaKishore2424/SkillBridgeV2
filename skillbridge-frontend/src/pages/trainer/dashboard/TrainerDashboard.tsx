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
  Badge,
} from '@/shared/components/ui'
import { StatCardSkeleton, ListSkeleton } from '@/shared/components/ui/loading-skeleton'
import {
  getTrainerDashboardStats,
  getTrainerBatches,
} from '@/api/trainer'
import { ArrowRight, BookOpen, Clock, GraduationCap, Users } from 'lucide-react'
import { itemsOf } from '@/api/paging'
import { EmptyState, ErrorState, PageHeader, StatCard } from '@/shared/components/page'

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
    queryFn: () => getTrainerBatches({ size: 100 }),
    select: itemsOf,
  })

  return (
    <RoleGuard allowedRoles={['TRAINER']}>
      <AuthenticatedLayout>
        <PageWrapper>
          <div className="space-y-6">
            <PageHeader
              title="Your batches"
              description="What you are teaching, and who is waiting on a grade."
              actions={
                <Button variant="outline" asChild>
                  <Link to="/trainer/students">
                    My students
                    <ArrowRight className="ml-2 h-4 w-4" />
                  </Link>
                </Button>
              }
            />

            {statsError && <ErrorState error={statsError} title="Could not load your figures" />}
            {batchesError && <ErrorState error={batchesError} title="Could not load your batches" />}

            {/* Statistics Cards */}
            {statsLoading ? (
              <div className="grid gap-4 sm:grid-cols-3">
                {[0, 1, 2].map((index) => (
                  <StatCardSkeleton key={index} />
                ))}
              </div>
            ) : (
              <div className="grid gap-4 sm:grid-cols-3">
                <StatCard
                  label="Batches"
                  value={stats?.assignedBatches ?? 0}
                  hint={`${stats?.activeBatches ?? 0} running now`}
                  icon={BookOpen}
                />
                <StatCard
                  label="Students"
                  value={stats?.totalStudents ?? 0}
                  hint="Across every batch you teach"
                  icon={Users}
                />
                <StatCard
                  label="Topics to grade"
                  value={stats?.pendingProgressUpdates ?? 0}
                  hint="Nobody has recorded an outcome yet"
                  icon={Clock}
                />
              </div>
            )}

            {/* Assigned Batches */}
            <Card>
              <CardHeader>
                <CardTitle>Batches you teach</CardTitle>
                <CardDescription>
                  Open one to see its syllabus and grade a topic.
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
                                Open
                                <ArrowRight className="ml-2 h-4 w-4" />
                              </Link>
                            </Button>
                          </div>
                        </CardContent>
                      </Card>
                    ))}
                  </div>
                ) : (
                  <EmptyState
                    icon={BookOpen}
                    title="No batches assigned yet"
                    description="A college admin assigns trainers on the batch itself. They will appear here."
                  />
                )}
              </CardContent>
            </Card>
          </div>
        </PageWrapper>
      </AuthenticatedLayout>
    </RoleGuard>
  )
}

