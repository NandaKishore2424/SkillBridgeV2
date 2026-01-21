/**
 * Students List Page
 * 
 * College Admin page to view and manage all students
 * Features:
 * - Table with all students
 * - Search functionality
 * - Status badges
 * - Actions: View, Edit, Activate/Deactivate
 */

import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { AuthenticatedLayout } from '@/shared/components/layout'
import { PageWrapper } from '@/shared/components/layout'
import { RoleGuard } from '@/shared/components/auth'
import {
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Input,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  Badge,
  Alert,
  AlertDescription,
} from '@/shared/components/ui'
import { TableSkeleton } from '@/shared/components/ui/loading-skeleton'
import {
  getStudents,
  updateStudentStatus,
  type StudentWithDetails,
} from '@/api/college-admin'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import {
  Search,
  GraduationCap,
  MoreVertical,
  Edit,
  Power,
  Loader2,
  AlertCircle,
  Upload,
} from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/shared/components/ui'

export function StudentsList() {
  const queryClient = useQueryClient()
  const [searchQuery, setSearchQuery] = useState('')
  const { showSuccess, showError } = useToastNotifications()

  // Fetch students
  const {
    data: students,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['admin', 'students'],
    queryFn: getStudents,
  })

  // Update student status mutation
  const statusMutation = useMutation({
    mutationFn: ({ id, isActive }: { id: number; isActive: boolean }) =>
      updateStudentStatus(id, isActive),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'students'] })
      showSuccess(
        `Student ${variables.isActive ? 'activated' : 'deactivated'} successfully`
      )
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to update student status')
    },
  })

  // Filter students
  const filteredStudents = students?.filter((student) => {
    if (!searchQuery) return true
    const query = searchQuery.toLowerCase()
    return (
      student.rollNumber.toLowerCase().includes(query) ||
      student.email.toLowerCase().includes(query) ||  // Fixed: email is on student directly
      student.degree?.toLowerCase().includes(query) ||
      student.branch?.toLowerCase().includes(query)
    )
  })

  const handleStatusToggle = (student: StudentWithDetails) => {
    const newStatus = !student.isActive
    if (
      confirm(
        `Are you sure you want to ${newStatus ? 'activate' : 'deactivate'} student ${student.rollNumber}?`
      )
    ) {
      statusMutation.mutate({ id: student.id, isActive: newStatus })
    }
  }

  return (
    <RoleGuard allowedRoles={['COLLEGE_ADMIN']}>
      <AuthenticatedLayout>
        <PageWrapper>
          <div className="space-y-6">
            {/* Header */}
            <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
              <div>
                <h1 className="text-3xl font-bold tracking-tight">Students</h1>
                <p className="text-muted-foreground">
                  Manage students in your college
                </p>
              </div>
            </div>

            {/* Search and Action */}
            <div className="flex gap-4 items-center">
              <Card className="flex-1">
                <CardContent className="pt-6">
                  <div className="relative">
                    <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                    <Input
                      placeholder="Search students by roll number, email, degree, or branch..."
                      value={searchQuery}
                      onChange={(e) => setSearchQuery(e.target.value)}
                      className="pl-10"
                    />
                  </div>
                </CardContent>
              </Card>
              <Button asChild>
                <Link to="/admin/students/upload">
                  <Upload className="mr-2 h-4 w-4" />
                  Bulk Upload
                </Link>
              </Button>
            </div>

            {/* Error Alert */}
            {error && (
              <Alert variant="destructive">
                <AlertCircle className="h-4 w-4" />
                <AlertDescription>
                  Failed to load students. Please try again.
                </AlertDescription>
              </Alert>
            )}

            {/* Students Table */}
            <Card>
              <CardHeader>
                <CardTitle>All Students</CardTitle>
                <CardDescription>
                  {filteredStudents?.length || 0} student(s) found
                </CardDescription>
              </CardHeader>
              <CardContent>
                {isLoading ? (
                  <TableSkeleton rows={5} columns={8} />
                ) : filteredStudents && filteredStudents.length > 0 ? (
                  <div className="rounded-md border">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>Roll Number</TableHead>
                          <TableHead>Email</TableHead>
                          <TableHead>Degree</TableHead>
                          <TableHead>Branch</TableHead>
                          <TableHead>Year</TableHead>
                          <TableHead>Enrolled Batches</TableHead>
                          <TableHead>Status</TableHead>
                          <TableHead className="text-right">Actions</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {filteredStudents.map((student) => (
                          <TableRow key={student.id}>
                            <TableCell className="font-medium">
                              <div className="flex items-center gap-2">
                                <GraduationCap className="h-4 w-4 text-muted-foreground" />
                                {student.rollNumber}
                              </div>
                            </TableCell>
                            <TableCell>{student.email}</TableCell>
                            <TableCell>{student.degree || '-'}</TableCell>
                            <TableCell>{student.branch || '-'}</TableCell>
                            <TableCell>{student.year || '-'}</TableCell>
                            <TableCell>
                              {student.enrolledBatchIds && student.enrolledBatchIds.length > 0 ? (
                                <span className="text-sm">
                                  {student.enrolledBatchIds.length} batch(es)
                                </span>
                              ) : (
                                <span className="text-muted-foreground">None</span>
                              )}
                            </TableCell>
                            <TableCell>
                              <Badge variant={student.isActive ? 'default' : 'secondary'}>
                                {student.isActive ? 'Active' : 'Inactive'}
                              </Badge>
                            </TableCell>
                            <TableCell className="text-right">
                              <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                  <Button variant="ghost" size="icon">
                                    <MoreVertical className="h-4 w-4" />
                                  </Button>
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="end">
                                  <DropdownMenuItem asChild>
                                    <Link to={`/admin/students/${student.id}`}>
                                      <Edit className="mr-2 h-4 w-4" />
                                      View Details
                                    </Link>
                                  </DropdownMenuItem>
                                  <DropdownMenuItem
                                    onClick={() => handleStatusToggle(student)}
                                    disabled={statusMutation.isPending}
                                  >
                                    <Power className="mr-2 h-4 w-4" />
                                    {student.isActive ? 'Deactivate' : 'Activate'}
                                  </DropdownMenuItem>
                                </DropdownMenuContent>
                              </DropdownMenu>
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </div>
                ) : (
                  <div className="text-center py-12">
                    <GraduationCap className="mx-auto h-12 w-12 text-muted-foreground mb-4" />
                    <h3 className="text-lg font-semibold mb-2">No students found</h3>
                    <p className="text-muted-foreground">
                      {searchQuery
                        ? 'Try adjusting your search query'
                        : 'Students will appear here once they register'}
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

