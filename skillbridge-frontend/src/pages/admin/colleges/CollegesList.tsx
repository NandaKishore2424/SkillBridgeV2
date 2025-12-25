/**
 * Colleges List Page
 * 
 * System Admin page to view and manage all colleges
 * Features:
 * - Table with all colleges
 * - Search/filter functionality
 * - Actions: View, Edit, Deactivate/Activate
 * - Create college button
 */

import { useState, useEffect } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { AuthenticatedLayout } from '@/shared/components/layout'
import { PageWrapper } from '@/shared/components/layout'
import { RoleGuard } from '@/shared/components/auth'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
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
  TableSkeleton,
} from '@/shared/components/ui'
import {
  getAllColleges,
  updateCollegeStatus,
  type College,
} from '@/api/admin'
import { Plus, Search, Building2, MoreVertical, Edit, Power, Loader2, AlertCircle } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/shared/components/ui'

export function CollegesList() {
  const queryClient = useQueryClient()
  const location = useLocation()
  const navigate = useNavigate()
  const [searchQuery, setSearchQuery] = useState('')
  const { showSuccess, showError } = useToastNotifications()

  // Show success message from navigation state
  useEffect(() => {
    if (location.state?.message) {
      showSuccess(location.state.message)
      // Clear the state
      window.history.replaceState({}, document.title)
    }
  }, [location.state, showSuccess])

  // Fetch colleges
  const {
    data: colleges,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['admin', 'colleges'],
    queryFn: getAllColleges,
  })

  // Update college status mutation
  const statusMutation = useMutation({
    mutationFn: ({ id, status }: { id: number; status: 'ACTIVE' | 'INACTIVE' }) =>
      updateCollegeStatus(id, status),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'colleges'] })
      showSuccess(
        `College ${variables.status === 'ACTIVE' ? 'activated' : 'deactivated'} successfully`
      )
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to update college status')
    },
  })

  // Filter colleges based on search query
  const filteredColleges = colleges?.filter((college) => {
    if (!searchQuery) return true
    const query = searchQuery.toLowerCase()
    return (
      college.name.toLowerCase().includes(query) ||
      college.code.toLowerCase().includes(query) ||
      college.email?.toLowerCase().includes(query)
    )
  })

  const handleStatusToggle = (college: College) => {
    const newStatus = college.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
    if (
      confirm(
        `Are you sure you want to ${newStatus === 'ACTIVE' ? 'activate' : 'deactivate'} ${college.name}?`
      )
    ) {
      statusMutation.mutate({ id: college.id, status: newStatus })
    }
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
                  Colleges
                </h1>
                <p className="text-muted-foreground mt-2">
                  Manage all colleges and institutions on the platform
                </p>
              </div>
              <Button asChild size="lg" className="shadow-md hover:shadow-lg transition-shadow">
                <Link to="/admin/colleges/create">
                  <Plus className="mr-2 h-4 w-4" />
                  Create College
                </Link>
              </Button>
            </div>

            {/* Search */}
            <Card>
              <CardContent className="pt-6">
                <div className="relative">
                  <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                  <Input
                    placeholder="Search colleges by name, code, or email..."
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    className="pl-10"
                  />
                </div>
              </CardContent>
            </Card>

            {/* Error Alert */}
            {error && (
              <Alert variant="destructive">
                <AlertCircle className="h-4 w-4" />
                <AlertDescription>
                  Failed to load colleges. Please try again.
                </AlertDescription>
              </Alert>
            )}

            {/* Colleges Table */}
            <Card className="shadow-sm hover:shadow-md transition-shadow">
              <CardHeader className="border-b">
                <CardTitle className="text-xl">All Colleges</CardTitle>
                <CardDescription>
                  {filteredColleges?.length || 0} college(s) found
                </CardDescription>
              </CardHeader>
              <CardContent className="p-0">
                {isLoading ? (
                  <TableSkeleton rows={5} columns={6} />
                ) : filteredColleges && filteredColleges.length > 0 ? (
                  <div className="overflow-x-auto">
                    <Table>
                      <TableHeader>
                        <TableRow className="bg-muted/50">
                          <TableHead className="font-semibold">Name</TableHead>
                          <TableHead className="font-semibold">Code</TableHead>
                          <TableHead className="font-semibold">Email</TableHead>
                          <TableHead className="font-semibold">Phone</TableHead>
                          <TableHead className="font-semibold">Status</TableHead>
                          <TableHead className="text-right font-semibold">Actions</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {filteredColleges.map((college) => (
                          <TableRow 
                            key={college.id}
                            className="cursor-pointer hover:bg-muted/50 transition-colors"
                            onClick={() => navigate(`/admin/colleges/${college.id}`)}
                          >
                            <TableCell className="font-medium">
                              <div className="flex items-center gap-2">
                                <Building2 className="h-4 w-4 text-primary" />
                                <span className="hover:text-primary transition-colors">{college.name}</span>
                              </div>
                            </TableCell>
                            <TableCell>
                              <code className="text-sm bg-muted px-2 py-1 rounded">
                                {college.code}
                              </code>
                            </TableCell>
                            <TableCell>{college.email || '-'}</TableCell>
                            <TableCell>{college.phone || '-'}</TableCell>
                            <TableCell>
                              <Badge
                                variant={college.status === 'ACTIVE' ? 'default' : 'secondary'}
                              >
                                {college.status}
                              </Badge>
                            </TableCell>
                            <TableCell className="text-right" onClick={(e) => e.stopPropagation()}>
                              <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                  <Button variant="ghost" size="icon" className="hover:bg-muted">
                                    <MoreVertical className="h-4 w-4" />
                                  </Button>
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="end">
                                  <DropdownMenuItem 
                                    onClick={() => navigate(`/admin/colleges/${college.id}`)}
                                  >
                                    <Edit className="mr-2 h-4 w-4" />
                                    View Details
                                  </DropdownMenuItem>
                                  <DropdownMenuItem
                                    onClick={() => handleStatusToggle(college)}
                                    disabled={statusMutation.isPending}
                                  >
                                    <Power className="mr-2 h-4 w-4" />
                                    {college.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
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
                    <Building2 className="mx-auto h-12 w-12 text-muted-foreground mb-4" />
                    <h3 className="text-lg font-semibold mb-2">No colleges found</h3>
                    <p className="text-muted-foreground mb-4">
                      {searchQuery
                        ? 'Try adjusting your search query'
                        : 'Get started by creating your first college'}
                    </p>
                    {!searchQuery && (
                      <Button asChild>
                        <Link to="/admin/colleges/create">
                          <Plus className="mr-2 h-4 w-4" />
                          Create College
                        </Link>
                      </Button>
                    )}
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

