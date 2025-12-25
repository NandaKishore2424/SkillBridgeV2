/**
 * College Detail Page
 * 
 * System Admin page to view and manage a specific college
 * Features:
 * - College overview with statistics
 * - Tabs: Overview | Students | Batches | Trainers | Admins
 * - Create Admin button in Admins tab
 * - Professional tabbed interface
 */

import { useState } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
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
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/shared/components/ui'
import { TableSkeleton, StatCardSkeleton } from '@/shared/components/ui'
import {
  getCollegeById,
  getCollegeStudents,
  getCollegeBatches,
  getCollegeTrainers,
  getCollegeAdmins,
  createCollegeAdmin,
  type CreateCollegeAdminRequest,
} from '@/api/admin'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import {
  Building2,
  ArrowLeft,
  Users,
  GraduationCap,
  BookOpen,
  UserCog,
  Plus,
  Mail,
  Phone,
  MapPin,
  CheckCircle2,
  XCircle,
  AlertCircle,
  Loader2,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { CreateCollegeAdminModal } from './CreateCollegeAdminModal'

export function CollegeDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const collegeId = id ? parseInt(id) : 0
  const [activeTab, setActiveTab] = useState('overview')
  const [showCreateAdminModal, setShowCreateAdminModal] = useState(false)
  const { showSuccess, showError } = useToastNotifications()

  // Fetch college details
  const {
    data: college,
    isLoading: collegeLoading,
    error: collegeError,
  } = useQuery({
    queryKey: ['admin', 'colleges', collegeId],
    queryFn: () => getCollegeById(collegeId),
    enabled: !!collegeId,
  })

  // Fetch college students
  const {
    data: students,
    isLoading: studentsLoading,
    error: studentsError,
  } = useQuery({
    queryKey: ['admin', 'colleges', collegeId, 'students'],
    queryFn: () => getCollegeStudents(collegeId),
    enabled: !!collegeId && activeTab === 'students',
  })

  // Fetch college batches
  const {
    data: batches,
    isLoading: batchesLoading,
    error: batchesError,
  } = useQuery({
    queryKey: ['admin', 'colleges', collegeId, 'batches'],
    queryFn: () => getCollegeBatches(collegeId),
    enabled: !!collegeId && activeTab === 'batches',
  })

  // Fetch college trainers
  const {
    data: trainers,
    isLoading: trainersLoading,
    error: trainersError,
  } = useQuery({
    queryKey: ['admin', 'colleges', collegeId, 'trainers'],
    queryFn: () => getCollegeTrainers(collegeId),
    enabled: !!collegeId && activeTab === 'trainers',
  })

  // Fetch college admins
  const {
    data: admins,
    isLoading: adminsLoading,
    error: adminsError,
    refetch: refetchAdmins,
  } = useQuery({
    queryKey: ['admin', 'colleges', collegeId, 'admins'],
    queryFn: () => getCollegeAdmins(collegeId),
    enabled: !!collegeId && activeTab === 'admins',
  })

  const handleCreateAdminSuccess = () => {
    setShowCreateAdminModal(false)
    refetchAdmins()
    showSuccess('College admin created successfully!')
  }

  if (collegeLoading) {
    return (
      <RoleGuard allowedRoles={['SYSTEM_ADMIN']}>
        <AuthenticatedLayout>
          <PageWrapper>
            <div className="space-y-6">
              <div className="h-8 w-64 bg-muted animate-pulse rounded" />
              <div className="grid gap-4 md:grid-cols-4">
                <StatCardSkeleton />
                <StatCardSkeleton />
                <StatCardSkeleton />
                <StatCardSkeleton />
              </div>
            </div>
          </PageWrapper>
        </AuthenticatedLayout>
      </RoleGuard>
    )
  }

  if (collegeError || !college) {
    return (
      <RoleGuard allowedRoles={['SYSTEM_ADMIN']}>
        <AuthenticatedLayout>
          <PageWrapper>
            <Alert variant="destructive">
              <AlertCircle className="h-4 w-4" />
              <AlertDescription>
                Failed to load college details. Please try again.
              </AlertDescription>
            </Alert>
          </PageWrapper>
        </AuthenticatedLayout>
      </RoleGuard>
    )
  }

  return (
    <RoleGuard allowedRoles={['SYSTEM_ADMIN']}>
      <AuthenticatedLayout>
        <PageWrapper>
          <div className="space-y-6">
            {/* Header */}
            <div className="flex items-center gap-4">
              <Button
                variant="ghost"
                size="icon"
                onClick={() => navigate('/admin/colleges')}
                className="hover:bg-muted"
              >
                <ArrowLeft className="h-4 w-4" />
              </Button>
              <div className="flex-1">
                <div className="flex items-center gap-3">
                  <div className="h-12 w-12 rounded-lg bg-primary/10 flex items-center justify-center">
                    <Building2 className="h-6 w-6 text-primary" />
                  </div>
                  <div>
                    <h1 className="text-4xl font-bold tracking-tight bg-gradient-to-r from-foreground to-foreground/70 bg-clip-text text-transparent">
                      {college.name}
                    </h1>
                    <div className="flex items-center gap-2 mt-1">
                      <code className="text-sm bg-muted px-2 py-1 rounded font-mono">
                        {college.code}
                      </code>
                      <Badge
                        variant={college.status === 'ACTIVE' ? 'default' : 'secondary'}
                      >
                        {college.status === 'ACTIVE' ? (
                          <CheckCircle2 className="h-3 w-3 mr-1" />
                        ) : (
                          <XCircle className="h-3 w-3 mr-1" />
                        )}
                        {college.status}
                      </Badge>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* College Info Card */}
            <Card className="border-l-4 border-l-primary shadow-sm">
              <CardHeader>
                <CardTitle>College Information</CardTitle>
                <CardDescription>Contact and location details</CardDescription>
              </CardHeader>
              <CardContent>
                <div className="grid gap-4 md:grid-cols-2">
                  {college.email && (
                    <div className="flex items-center gap-3">
                      <Mail className="h-4 w-4 text-muted-foreground" />
                      <div>
                        <p className="text-sm font-medium">Email</p>
                        <p className="text-sm text-muted-foreground">{college.email}</p>
                      </div>
                    </div>
                  )}
                  {college.phone && (
                    <div className="flex items-center gap-3">
                      <Phone className="h-4 w-4 text-muted-foreground" />
                      <div>
                        <p className="text-sm font-medium">Phone</p>
                        <p className="text-sm text-muted-foreground">{college.phone}</p>
                      </div>
                    </div>
                  )}
                  {college.address && (
                    <div className="flex items-center gap-3 md:col-span-2">
                      <MapPin className="h-4 w-4 text-muted-foreground" />
                      <div>
                        <p className="text-sm font-medium">Address</p>
                        <p className="text-sm text-muted-foreground">{college.address}</p>
                      </div>
                    </div>
                  )}
                </div>
              </CardContent>
            </Card>

            {/* Tabs */}
            <Tabs value={activeTab} onValueChange={setActiveTab} className="space-y-4">
              <TabsList className="grid w-full grid-cols-5">
                <TabsTrigger value="overview">Overview</TabsTrigger>
                <TabsTrigger value="students">Students</TabsTrigger>
                <TabsTrigger value="batches">Batches</TabsTrigger>
                <TabsTrigger value="trainers">Trainers</TabsTrigger>
                <TabsTrigger value="admins">Admins</TabsTrigger>
              </TabsList>

              {/* Overview Tab */}
              <TabsContent value="overview" className="space-y-4">
                <div className="grid gap-4 md:grid-cols-4">
                  <Card className="hover:shadow-md transition-shadow">
                    <CardHeader className="pb-2">
                      <CardTitle className="text-sm font-medium text-muted-foreground">
                        Total Students
                      </CardTitle>
                    </CardHeader>
                    <CardContent>
                      <div className="text-2xl font-bold">
                        {students?.length || 0}
                      </div>
                    </CardContent>
                  </Card>
                  <Card className="hover:shadow-md transition-shadow">
                    <CardHeader className="pb-2">
                      <CardTitle className="text-sm font-medium text-muted-foreground">
                        Total Batches
                      </CardTitle>
                    </CardHeader>
                    <CardContent>
                      <div className="text-2xl font-bold">
                        {batches?.length || 0}
                      </div>
                    </CardContent>
                  </Card>
                  <Card className="hover:shadow-md transition-shadow">
                    <CardHeader className="pb-2">
                      <CardTitle className="text-sm font-medium text-muted-foreground">
                        Total Trainers
                      </CardTitle>
                    </CardHeader>
                    <CardContent>
                      <div className="text-2xl font-bold">
                        {trainers?.length || 0}
                      </div>
                    </CardContent>
                  </Card>
                  <Card className="hover:shadow-md transition-shadow">
                    <CardHeader className="pb-2">
                      <CardTitle className="text-sm font-medium text-muted-foreground">
                        Admins
                      </CardTitle>
                    </CardHeader>
                    <CardContent>
                      <div className="text-2xl font-bold">
                        {admins?.length || 0}
                      </div>
                    </CardContent>
                  </Card>
                </div>
              </TabsContent>

              {/* Students Tab */}
              <TabsContent value="students" className="space-y-4">
                {studentsLoading ? (
                  <TableSkeleton rows={5} columns={5} />
                ) : studentsError ? (
                  <Alert variant="destructive">
                    <AlertCircle className="h-4 w-4" />
                    <AlertDescription>
                      Failed to load students. Please try again.
                    </AlertDescription>
                  </Alert>
                ) : students && students.length > 0 ? (
                  <Card>
                    <CardHeader>
                      <CardTitle>Students</CardTitle>
                      <CardDescription>
                        {students.length} student(s) enrolled in this college
                      </CardDescription>
                    </CardHeader>
                    <CardContent>
                      <div className="rounded-md border">
                        <Table>
                          <TableHeader>
                            <TableRow>
                              <TableHead>Name</TableHead>
                              <TableHead>Email</TableHead>
                              <TableHead>Roll Number</TableHead>
                              <TableHead>Status</TableHead>
                            </TableRow>
                          </TableHeader>
                          <TableBody>
                            {students.map((student: any) => (
                              <TableRow key={student.id}>
                                <TableCell className="font-medium">{student.name || student.fullName || '-'}</TableCell>
                                <TableCell>{student.email || '-'}</TableCell>
                                <TableCell>{student.rollNumber || '-'}</TableCell>
                                <TableCell>
                                  <Badge variant={student.isActive ? 'default' : 'secondary'}>
                                    {student.isActive ? 'Active' : 'Inactive'}
                                  </Badge>
                                </TableCell>
                              </TableRow>
                            ))}
                          </TableBody>
                        </Table>
                      </div>
                    </CardContent>
                  </Card>
                ) : (
                  <Card>
                    <CardContent className="flex flex-col items-center justify-center py-12">
                      <GraduationCap className="h-12 w-12 text-muted-foreground mb-4" />
                      <h3 className="text-lg font-semibold mb-2">No students yet</h3>
                      <p className="text-sm text-muted-foreground">
                        Students will appear here once they register for this college.
                      </p>
                    </CardContent>
                  </Card>
                )}
              </TabsContent>

              {/* Batches Tab */}
              <TabsContent value="batches" className="space-y-4">
                {batchesLoading ? (
                  <TableSkeleton rows={5} columns={5} />
                ) : batchesError ? (
                  <Alert variant="destructive">
                    <AlertCircle className="h-4 w-4" />
                    <AlertDescription>
                      Failed to load batches. Please try again.
                    </AlertDescription>
                  </Alert>
                ) : batches && batches.length > 0 ? (
                  <Card>
                    <CardHeader>
                      <CardTitle>Batches</CardTitle>
                      <CardDescription>
                        {batches.length} batch(es) in this college
                      </CardDescription>
                    </CardHeader>
                    <CardContent>
                      <div className="rounded-md border">
                        <Table>
                          <TableHeader>
                            <TableRow>
                              <TableHead>Name</TableHead>
                              <TableHead>Status</TableHead>
                              <TableHead>Start Date</TableHead>
                              <TableHead>End Date</TableHead>
                            </TableRow>
                          </TableHeader>
                          <TableBody>
                            {batches.map((batch: any) => (
                              <TableRow key={batch.id}>
                                <TableCell className="font-medium">{batch.name || '-'}</TableCell>
                                <TableCell>
                                  <Badge variant={batch.status === 'ACTIVE' ? 'default' : 'secondary'}>
                                    {batch.status || '-'}
                                  </Badge>
                                </TableCell>
                                <TableCell>{batch.startDate ? new Date(batch.startDate).toLocaleDateString() : '-'}</TableCell>
                                <TableCell>{batch.endDate ? new Date(batch.endDate).toLocaleDateString() : '-'}</TableCell>
                              </TableRow>
                            ))}
                          </TableBody>
                        </Table>
                      </div>
                    </CardContent>
                  </Card>
                ) : (
                  <Card>
                    <CardContent className="flex flex-col items-center justify-center py-12">
                      <BookOpen className="h-12 w-12 text-muted-foreground mb-4" />
                      <h3 className="text-lg font-semibold mb-2">No batches yet</h3>
                      <p className="text-sm text-muted-foreground">
                        Batches will appear here once they are created for this college.
                      </p>
                    </CardContent>
                  </Card>
                )}
              </TabsContent>

              {/* Trainers Tab */}
              <TabsContent value="trainers" className="space-y-4">
                {trainersLoading ? (
                  <TableSkeleton rows={5} columns={5} />
                ) : trainersError ? (
                  <Alert variant="destructive">
                    <AlertCircle className="h-4 w-4" />
                    <AlertDescription>
                      Failed to load trainers. Please try again.
                    </AlertDescription>
                  </Alert>
                ) : trainers && trainers.length > 0 ? (
                  <Card>
                    <CardHeader>
                      <CardTitle>Trainers</CardTitle>
                      <CardDescription>
                        {trainers.length} trainer(s) in this college
                      </CardDescription>
                    </CardHeader>
                    <CardContent>
                      <div className="rounded-md border">
                        <Table>
                          <TableHeader>
                            <TableRow>
                              <TableHead>Name</TableHead>
                              <TableHead>Email</TableHead>
                              <TableHead>Department</TableHead>
                              <TableHead>Status</TableHead>
                            </TableRow>
                          </TableHeader>
                          <TableBody>
                            {trainers.map((trainer: any) => (
                              <TableRow key={trainer.id}>
                                <TableCell className="font-medium">{trainer.name || trainer.fullName || '-'}</TableCell>
                                <TableCell>{trainer.email || '-'}</TableCell>
                                <TableCell>{trainer.department || '-'}</TableCell>
                                <TableCell>
                                  <Badge variant={trainer.isActive ? 'default' : 'secondary'}>
                                    {trainer.isActive ? 'Active' : 'Inactive'}
                                  </Badge>
                                </TableCell>
                              </TableRow>
                            ))}
                          </TableBody>
                        </Table>
                      </div>
                    </CardContent>
                  </Card>
                ) : (
                  <Card>
                    <CardContent className="flex flex-col items-center justify-center py-12">
                      <Users className="h-12 w-12 text-muted-foreground mb-4" />
                      <h3 className="text-lg font-semibold mb-2">No trainers yet</h3>
                      <p className="text-sm text-muted-foreground">
                        Trainers will appear here once they are added to this college.
                      </p>
                    </CardContent>
                  </Card>
                )}
              </TabsContent>

              {/* Admins Tab */}
              <TabsContent value="admins" className="space-y-4">
                <div className="flex justify-between items-center">
                  <div>
                    <h3 className="text-lg font-semibold">College Administrators</h3>
                    <p className="text-sm text-muted-foreground">
                      Manage administrators for this college
                    </p>
                  </div>
                  <Button onClick={() => setShowCreateAdminModal(true)}>
                    <Plus className="mr-2 h-4 w-4" />
                    Create Admin
                  </Button>
                </div>

                {adminsLoading ? (
                  <TableSkeleton rows={5} columns={4} />
                ) : adminsError ? (
                  <Alert variant="destructive">
                    <AlertCircle className="h-4 w-4" />
                    <AlertDescription>
                      Failed to load admins. Please try again.
                    </AlertDescription>
                  </Alert>
                ) : admins && admins.length > 0 ? (
                  <Card>
                    <CardHeader>
                      <CardTitle>Administrators</CardTitle>
                      <CardDescription>
                        {admins.length} administrator(s) for this college
                      </CardDescription>
                    </CardHeader>
                    <CardContent>
                      <div className="rounded-md border">
                        <Table>
                          <TableHeader>
                            <TableRow>
                              <TableHead>Name</TableHead>
                              <TableHead>Email</TableHead>
                              <TableHead>Phone</TableHead>
                              <TableHead>Status</TableHead>
                            </TableRow>
                          </TableHeader>
                          <TableBody>
                            {admins.map((admin: any) => (
                              <TableRow key={admin.id}>
                                <TableCell className="font-medium">{admin.name || admin.fullName || '-'}</TableCell>
                                <TableCell>{admin.email || '-'}</TableCell>
                                <TableCell>{admin.phone || '-'}</TableCell>
                                <TableCell>
                                  <Badge variant={admin.isActive ? 'default' : 'secondary'}>
                                    {admin.isActive ? 'Active' : 'Inactive'}
                                  </Badge>
                                </TableCell>
                              </TableRow>
                            ))}
                          </TableBody>
                        </Table>
                      </div>
                    </CardContent>
                  </Card>
                ) : (
                  <Card>
                    <CardContent className="flex flex-col items-center justify-center py-12">
                      <UserCog className="h-12 w-12 text-muted-foreground mb-4" />
                      <h3 className="text-lg font-semibold mb-2">No administrators yet</h3>
                      <p className="text-sm text-muted-foreground mb-4">
                        Create an administrator account to manage this college.
                      </p>
                      <Button onClick={() => setShowCreateAdminModal(true)}>
                        <Plus className="mr-2 h-4 w-4" />
                        Create First Admin
                      </Button>
                    </CardContent>
                  </Card>
                )}
              </TabsContent>
            </Tabs>

            {/* Create Admin Modal */}
            {showCreateAdminModal && college && (
              <CreateCollegeAdminModal
                collegeId={college.id}
                collegeName={college.name}
                open={showCreateAdminModal}
                onClose={() => setShowCreateAdminModal(false)}
                onSuccess={handleCreateAdminSuccess}
              />
            )}
          </div>
        </PageWrapper>
      </AuthenticatedLayout>
    </RoleGuard>
  )
}

