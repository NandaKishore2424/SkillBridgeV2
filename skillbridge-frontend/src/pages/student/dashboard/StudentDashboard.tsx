/**
 * Student Dashboard
 * 
 * Student's main dashboard showing:
 * - Statistics (enrolled batches, progress)
 * - Recommended batches
 * - Enrolled batches
 * - Available batches to browse
 */

import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
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
  Tabs,
  TabsList,
  TabsTrigger,
  TabsContent,
} from '@/shared/components/ui'
import { ListSkeleton, StatCardSkeleton } from '@/shared/components/ui/loading-skeleton'
import {
  getStudentDashboardStats,
  getRecommendedBatches,
  getAllAvailableBatches,
  getStudentBatches,
  applyToBatch,
} from '@/api/student'
import { itemsOf } from '@/api/paging'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import { MySkillGapCard } from '@/shared/components/skill-gap/SkillGapCard'
import { ErrorState, PageHeader, StatCard } from '@/shared/components/page'
import {
  BookOpen,
  TrendingUp,
  CheckCircle,
  Loader2,
  ArrowRight,
  Clock,
  GraduationCap,
  Users,
  Briefcase,
  Star,
  Plus,
} from 'lucide-react'

const STATUS_COLORS: Record<string, 'default' | 'secondary' | 'outline'> = {
  UPCOMING: 'outline',
  OPEN: 'default',
  ACTIVE: 'default',
  COMPLETED: 'secondary',
  CANCELLED: 'secondary',
}

export function StudentDashboard() {
  const queryClient = useQueryClient()
  const { showSuccess, showError } = useToastNotifications()
  const [activeTab, setActiveTab] = useState('recommended')

  const {
    data: stats,
    isLoading: statsLoading,
    error: statsError,
  } = useQuery({
    queryKey: ['student', 'dashboard', 'stats'],
    queryFn: getStudentDashboardStats,
  })

  const {
    data: recommendedBatches,
    isLoading: recommendedLoading,
  } = useQuery({
    queryKey: ['student', 'batches', 'recommended'],
    queryFn: getRecommendedBatches,
  })

  const {
    data: availableBatches,
    isLoading: availableLoading,
  } = useQuery({
    queryKey: ['student', 'batches', 'available'],
    // Paged. This card lists batches to apply to and has no pager yet, so it
    // takes the first page at the server's maximum size.
    queryFn: () => getAllAvailableBatches({ size: 100 }),
    select: itemsOf,
  })

  const {
    data: enrolledBatches,
    isLoading: enrolledLoading,
  } = useQuery({
    queryKey: ['student', 'batches', 'enrolled'],
    queryFn: () => getStudentBatches({ size: 100 }),
    select: itemsOf,
  })

  const applyMutation = useMutation({
    mutationFn: applyToBatch,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['student', 'batches'] })
      queryClient.invalidateQueries({ queryKey: ['student', 'batches', 'available'] })
      queryClient.invalidateQueries({ queryKey: ['student', 'batches', 'recommended'] })
      showSuccess('Application submitted successfully!')
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to apply to batch. Please try again.')
    },
  })

  const handleApply = (batchId: number) => {
    if (confirm('Apply to this batch?')) {
      applyMutation.mutate(batchId)
    }
  }

  return (
    <RoleGuard allowedRoles={['STUDENT']}>
      <AuthenticatedLayout>
        <PageWrapper>
          <div className="space-y-6">
            <PageHeader
              title="Your dashboard"
              description="Where you stand, and what to do next."
              actions={
                <Button variant="outline" asChild>
                  <Link to="/student/progress">
                    My progress
                    <ArrowRight className="ml-2 h-4 w-4" />
                  </Link>
                </Button>
              }
            />

            {statsError && <ErrorState error={statsError} title="Could not load your figures" />}

            {/* Statistics Cards */}
            {statsLoading ? (
              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                {[0, 1, 2, 3].map((index) => (
                  <StatCardSkeleton key={index} />
                ))}
              </div>
            ) : (
              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                {/*
                  Overall progress leads, because it is the one number a student
                  opens this page for. The server sends it and the frontend type
                  did not declare it, so it could not be shown at all until now
                  -- along with `upcomingBatches`, `pendingApplications` and
                  `totalTopicsAssigned`.
                */}
                <StatCard
                  label="Overall progress"
                  value={`${stats?.overallProgressPercent ?? 0}%`}
                  hint="Weighted across every batch you are in"
                  icon={TrendingUp}
                />
                <StatCard
                  label="Topics done"
                  value={stats?.totalTopicsCompleted ?? 0}
                  hint={`of ${stats?.totalTopicsAssigned ?? 0} assigned`}
                  icon={CheckCircle}
                />
                <StatCard
                  label="Batches"
                  value={stats?.enrolledBatches ?? 0}
                  hint={`${stats?.activeBatches ?? 0} running, ${stats?.completedBatches ?? 0} finished`}
                  icon={BookOpen}
                />
                <StatCard
                  label="Applications pending"
                  value={stats?.pendingApplications ?? 0}
                  hint="Waiting on an admin"
                  icon={Clock}
                />
              </div>
            )}

            <MySkillGapCard />

            {/* Tabs for Batches */}
            <Tabs value={activeTab} onValueChange={setActiveTab} className="space-y-4">
              <TabsList>
                <TabsTrigger value="recommended">
                  Recommended
                  {recommendedBatches && recommendedBatches.length > 0 && (
                    <Badge variant="secondary" className="ml-2">
                      {recommendedBatches.length}
                    </Badge>
                  )}
                </TabsTrigger>
                <TabsTrigger value="enrolled">
                  My Batches
                  {enrolledBatches && enrolledBatches.length > 0 && (
                    <Badge variant="secondary" className="ml-2">
                      {enrolledBatches.length}
                    </Badge>
                  )}
                </TabsTrigger>
                <TabsTrigger value="available">Browse All</TabsTrigger>
              </TabsList>

              {/* Recommended Batches */}
              <TabsContent value="recommended">
                <Card>
                  <CardHeader>
                    <CardTitle>Recommended for You</CardTitle>
                    <CardDescription>
                      Batches matched to your skills and interests
                    </CardDescription>
                  </CardHeader>
                  <CardContent>
                    {recommendedLoading ? (
                      <ListSkeleton items={3} />
                    ) : recommendedBatches && recommendedBatches.length > 0 ? (
                      <div className="space-y-4">
                        {recommendedBatches.map((batch) => (
                          <Card key={batch.id} className="hover:shadow-md transition-shadow">
                            <CardContent className="pt-6">
                              <div className="flex items-start justify-between">
                                <div className="flex-1">
                                  <div className="flex items-center gap-3 mb-2">
                                    <h3 className="text-lg font-semibold">{batch.name}</h3>
                                    <Badge variant={STATUS_COLORS[batch.status]}>
                                      {batch.status}
                                    </Badge>
                                    <div className="flex items-center gap-1 text-yellow-600">
                                      <Star className="h-4 w-4 fill-current" />
                                      <span className="text-sm font-medium">
                                        {batch.matchScore}% match
                                      </span>
                                    </div>
                                  </div>
                                  {batch.description && (
                                    <p className="text-sm text-muted-foreground mb-3">
                                      {batch.description}
                                    </p>
                                  )}
                                  {batch.matchReasons && batch.matchReasons.length > 0 && (
                                    <div className="mb-3">
                                      <p className="text-xs font-medium text-muted-foreground mb-1">
                                        Why this matches:
                                      </p>
                                      <ul className="text-xs text-muted-foreground list-disc list-inside">
                                        {batch.matchReasons.map((reason, idx) => (
                                          <li key={idx}>{reason}</li>
                                        ))}
                                      </ul>
                                    </div>
                                  )}
                                  <div className="flex items-center gap-4 text-sm text-muted-foreground">
                                    {batch.trainerNames && batch.trainerNames.length > 0 && (
                                      <div className="flex items-center gap-1">
                                        <Users className="h-4 w-4" />
                                        {batch.trainerNames.join(', ')}
                                      </div>
                                    )}
                                    {batch.companyNames && batch.companyNames.length > 0 && (
                                      <div className="flex items-center gap-1">
                                        <Briefcase className="h-4 w-4" />
                                        {batch.companyNames.length} companies
                                      </div>
                                    )}
                                    <div className="flex items-center gap-1">
                                      <GraduationCap className="h-4 w-4" />
                                      {batch.enrolledCount || 0}
                                      {batch.maxEnrollments && ` / ${batch.maxEnrollments}`}{' '}
                                      students
                                    </div>
                                  </div>
                                </div>
                                <div className="flex flex-col gap-2">
                                  {/*
                                    "View Details" used to sit here, pointing at
                                    `/student/batches/{id}`, which has never had
                                    a route -- so it returned the student to the
                                    landing page. It is gone rather than
                                    repointed: this card already shows the name,
                                    description, status, trainers, companies and
                                    enrolment count, and there is no student-side
                                    screen with more.
                                  */}
                                  <Button
                                    onClick={() => handleApply(batch.id)}
                                    disabled={applyMutation.isPending || batch.status !== 'OPEN'}
                                  >
                                    {applyMutation.isPending ? (
                                      <>
                                        <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                                        Applying...
                                      </>
                                    ) : (
                                      <>
                                        <Plus className="mr-2 h-4 w-4" />
                                        Apply
                                      </>
                                    )}
                                  </Button>

                                </div>
                              </div>
                            </CardContent>
                          </Card>
                        ))}
                      </div>
                    ) : (
                      <div className="text-center py-12">
                        <TrendingUp className="mx-auto h-12 w-12 text-muted-foreground mb-4" />
                        <h3 className="text-lg font-semibold mb-2">No recommendations yet</h3>
                        <p className="text-muted-foreground mb-4">
                          Complete your profile to get personalized batch recommendations
                        </p>
                        <Button variant="outline" onClick={() => setActiveTab('available')}>
                          Browse All Batches
                        </Button>
                      </div>
                    )}
                  </CardContent>
                </Card>
              </TabsContent>

              {/* Enrolled Batches */}
              <TabsContent value="enrolled">
                <Card>
                  <CardHeader>
                    <CardTitle>My Enrolled Batches</CardTitle>
                    <CardDescription>
                      Batches you are currently enrolled in
                    </CardDescription>
                  </CardHeader>
                  <CardContent>
                    {enrolledLoading ? (
                      <ListSkeleton items={3} />
                    ) : enrolledBatches && enrolledBatches.length > 0 ? (
                      <div className="space-y-4">
                        {enrolledBatches.map((batch) => (
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
                                  {batch.progress && (
                                    <div className="mb-3">
                                      <div className="flex items-center justify-between text-sm mb-1">
                                        <span className="text-muted-foreground">Progress</span>
                                        <span className="font-medium">
                                          {batch.progress.completionPercentage}%
                                        </span>
                                      </div>
                                      <div className="w-full bg-muted rounded-full h-2">
                                        <div
                                          className="bg-primary h-2 rounded-full transition-all"
                                          style={{
                                            width: `${batch.progress.completionPercentage}%`,
                                          }}
                                        />
                                      </div>
                                      <div className="flex items-center gap-4 text-xs text-muted-foreground mt-2">
                                        <span>
                                          {batch.progress.completedTopics} /{' '}
                                          {batch.progress.totalTopics} topics completed
                                        </span>
                                      </div>
                                    </div>
                                  )}
                                  <div className="flex items-center gap-4 text-sm text-muted-foreground">
                                    {batch.trainers && batch.trainers.length > 0 && (
                                      <div className="flex items-center gap-1">
                                        <Users className="h-4 w-4" />
                                        {batch.trainers.map((t) => t.fullName).join(', ')}
                                      </div>
                                    )}
                                    {batch.companies && batch.companies.length > 0 && (
                                      <div className="flex items-center gap-1">
                                        <Briefcase className="h-4 w-4" />
                                        {batch.companies.length} companies
                                      </div>
                                    )}
                                  </div>
                                </div>
                                {/*
                                  `/student/batches/{id}` had no route: this
                                  button quietly returned the student to the
                                  landing page. `/student/progress` is the
                                  screen that shows a batch's curriculum and
                                  status, selected by query parameter, so it is
                                  where "View progress" always meant to go.
                                */}
                                <Button variant="outline" asChild>
                                  <Link to={`/student/progress?batchId=${batch.id}`}>
                                    View progress
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
                        <h3 className="text-lg font-semibold mb-2">No enrolled batches</h3>
                        <p className="text-muted-foreground mb-4">
                          Apply to batches to start your learning journey
                        </p>
                        <Button onClick={() => setActiveTab('recommended')}>
                          View Recommendations
                        </Button>
                      </div>
                    )}
                  </CardContent>
                </Card>
              </TabsContent>

              {/* Available Batches */}
              <TabsContent value="available">
                <Card>
                  <CardHeader>
                    <CardTitle>All Available Batches</CardTitle>
                    <CardDescription>
                      Browse all batches open for enrollment
                    </CardDescription>
                  </CardHeader>
                  <CardContent>
                    {availableLoading ? (
                      <ListSkeleton items={3} />
                    ) : availableBatches && availableBatches.length > 0 ? (
                      <div className="space-y-4">
                        {availableBatches.map((batch) => (
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
                                </div>
                                <div className="flex flex-col gap-2">
                                  {/*
                                    "View Details" used to sit here, pointing at
                                    `/student/batches/{id}`, which has never had
                                    a route -- so it returned the student to the
                                    landing page. It is gone rather than
                                    repointed: this card already shows the name,
                                    description, status, trainers, companies and
                                    enrolment count, and there is no student-side
                                    screen with more.
                                  */}
                                  <Button
                                    onClick={() => handleApply(batch.id)}
                                    disabled={applyMutation.isPending || batch.status !== 'OPEN'}
                                  >
                                    {applyMutation.isPending ? (
                                      <>
                                        <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                                        Applying...
                                      </>
                                    ) : (
                                      <>
                                        <Plus className="mr-2 h-4 w-4" />
                                        Apply
                                      </>
                                    )}
                                  </Button>

                                </div>
                              </div>
                            </CardContent>
                          </Card>
                        ))}
                      </div>
                    ) : (
                      <div className="text-center py-12">
                        <BookOpen className="mx-auto h-12 w-12 text-muted-foreground mb-4" />
                        <h3 className="text-lg font-semibold mb-2">No batches available</h3>
                        <p className="text-muted-foreground">
                          Check back later for new batches
                        </p>
                      </div>
                    )}
                  </CardContent>
                </Card>
              </TabsContent>
            </Tabs>
          </div>
        </PageWrapper>
      </AuthenticatedLayout>
    </RoleGuard>
  )
}

