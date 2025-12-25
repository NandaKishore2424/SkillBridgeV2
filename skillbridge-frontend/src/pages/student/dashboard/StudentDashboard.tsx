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
  Alert,
  AlertDescription,
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
  type RecommendedBatch,
  type StudentBatch,
} from '@/api/student'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import {
  BookOpen,
  TrendingUp,
  CheckCircle,
  Loader2,
  AlertCircle,
  ArrowRight,
  GraduationCap,
  Users,
  Briefcase,
  Star,
  Plus,
} from 'lucide-react'

interface StatCardProps {
  title: string
  value: number | string
  description?: string
  icon: React.ReactNode
}

function StatCard({ title, value, description, icon }: StatCardProps) {
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
    queryFn: getAllAvailableBatches,
  })

  const {
    data: enrolledBatches,
    isLoading: enrolledLoading,
  } = useQuery({
    queryKey: ['student', 'batches', 'enrolled'],
    queryFn: getStudentBatches,
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
            {/* Header */}
            <div>
              <h1 className="text-3xl font-bold tracking-tight">Student Dashboard</h1>
              <p className="text-muted-foreground">
                Discover batches, track your progress, and advance your skills
              </p>
            </div>

            {/* Error Alert */}
            {statsError && (
              <Alert variant="destructive">
                <AlertCircle className="h-4 w-4" />
                <AlertDescription>
                  Failed to load dashboard statistics. Please try again.
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
                  title="Enrolled Batches"
                  value={stats?.enrolledBatches || 0}
                  description={`${stats?.activeBatches || 0} active`}
                  icon={<BookOpen className="h-4 w-4" />}
                />
                <StatCard
                  title="Completed Batches"
                  value={stats?.completedBatches || 0}
                  description="Successfully finished"
                  icon={<CheckCircle className="h-4 w-4" />}
                />
                <StatCard
                  title="Topics Completed"
                  value={stats?.totalTopicsCompleted || 0}
                  description="Across all batches"
                  icon={<TrendingUp className="h-4 w-4" />}
                />
              </div>
            )}

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
                                  <Button variant="outline" asChild>
                                    <Link to={`/student/batches/${batch.id}`}>
                                      View Details
                                    </Link>
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
                                <Button variant="outline" asChild>
                                  <Link to={`/student/batches/${batch.id}`}>
                                    View Progress
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
                                  <Button variant="outline" asChild>
                                    <Link to={`/student/batches/${batch.id}`}>
                                      View Details
                                    </Link>
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

