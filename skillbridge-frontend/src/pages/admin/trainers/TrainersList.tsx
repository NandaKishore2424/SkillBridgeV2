/**
 * Trainers List Page
 * 
 * College Admin page to view and manage all trainers
 * Features:
 * - Table with all trainers
 * - Search functionality
 * - Status badges
 * - Actions: View, Edit, Activate/Deactivate
 * - Create trainer button
 */

import { useState, useEffect } from 'react'
import { Link, useLocation } from 'react-router-dom'
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
  getTrainers,
  updateTrainerStatus,
  type Trainer,
} from '@/api/college-admin'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import {
  Plus,
  Search,
  Users,
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

export function TrainersList() {
  const queryClient = useQueryClient()
  const location = useLocation()
  const [searchQuery, setSearchQuery] = useState('')
  const { showSuccess, showError } = useToastNotifications()

  // Show success message from navigation state
  useEffect(() => {
    if (location.state?.message) {
      showSuccess(location.state.message)
      window.history.replaceState({}, document.title)
    }
  }, [location.state, showSuccess])

  // Fetch trainers
  const {
    data: trainers,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['admin', 'trainers'],
    queryFn: getTrainers,
  })

  // Update trainer status mutation
  const statusMutation = useMutation({
    mutationFn: ({ id, isActive }: { id: number; isActive: boolean }) =>
      updateTrainerStatus(id, isActive),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'trainers'] })
      showSuccess(
        `Trainer ${variables.isActive ? 'activated' : 'deactivated'} successfully`
      )
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to update trainer status')
    },
  })

  // Filter trainers
  const filteredTrainers = trainers?.filter((trainer) => {
    if (!searchQuery) return true
    const query = searchQuery.toLowerCase()
    return (
      trainer.fullName.toLowerCase().includes(query) ||
      trainer.email.toLowerCase().includes(query) ||
      trainer.department?.toLowerCase().includes(query) ||
      trainer.specialization?.toLowerCase().includes(query)
    )
  })

  const handleStatusToggle = (trainer: Trainer) => {
    const newStatus = !trainer.isActive
    if (
      confirm(
        `Are you sure you want to ${newStatus ? 'activate' : 'deactivate'} ${trainer.fullName}?`
      )
    ) {
      statusMutation.mutate({ id: trainer.id, isActive: newStatus })
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
                <h1 className="text-3xl font-bold tracking-tight">Trainers</h1>
                <p className="text-muted-foreground">
                  Manage trainers in your college
                </p>
              </div>
              <div className="flex gap-2">
                <Button variant="outline" asChild>
                  <Link to="/admin/trainers/upload">
                    <Upload className="mr-2 h-4 w-4" />
                    Bulk Upload
                  </Link>
                </Button>
                <Button asChild>
                  <Link to="/admin/trainers/create">
                    <Plus className="mr-2 h-4 w-4" />
                    Add Trainer
                  </Link>
                </Button>
              </div>
            </div>

            {/* Search */}
            <Card>
              <CardContent className="pt-6">
                <div className="relative">
                  <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                  <Input
                    placeholder="Search trainers by name, email, department, or specialization..."
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
                  Failed to load trainers. Please try again.
                </AlertDescription>
              </Alert>
            )}

            {/* Trainers Table */}
            <Card>
              <CardHeader>
                <CardTitle>All Trainers</CardTitle>
                <CardDescription>
                  {filteredTrainers?.length || 0} trainer(s) found
                </CardDescription>
              </CardHeader>
              <CardContent>
                {isLoading ? (
                  <TableSkeleton rows={5} columns={7} />
                ) : filteredTrainers && filteredTrainers.length > 0 ? (
                  <div className="rounded-md border">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>Name</TableHead>
                          <TableHead>Email</TableHead>
                          <TableHead>Department</TableHead>
                          <TableHead>Specialization</TableHead>
                          <TableHead>Assigned Batches</TableHead>
                          <TableHead>Status</TableHead>
                          <TableHead className="text-right">Actions</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {filteredTrainers.map((trainer) => (
                          <TableRow key={trainer.id}>
                            <TableCell className="font-medium">
                              <div className="flex items-center gap-2">
                                <Users className="h-4 w-4 text-muted-foreground" />
                                {trainer.fullName}
                              </div>
                            </TableCell>
                            <TableCell>{trainer.email}</TableCell>
                            <TableCell>{trainer.department || '-'}</TableCell>
                            <TableCell>{trainer.specialization || '-'}</TableCell>
                            <TableCell>
                              {trainer.assignedBatchIds && trainer.assignedBatchIds.length > 0 ? (
                                <span className="text-sm">
                                  {trainer.assignedBatchIds.length} batch(es)
                                </span>
                              ) : (
                                <span className="text-muted-foreground">None</span>
                              )}
                            </TableCell>
                            <TableCell>
                              <Badge variant={trainer.isActive ? 'default' : 'secondary'}>
                                {trainer.isActive ? 'Active' : 'Inactive'}
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
                                    <Link to={`/admin/trainers/${trainer.id}`}>
                                      <Edit className="mr-2 h-4 w-4" />
                                      View Details
                                    </Link>
                                  </DropdownMenuItem>
                                  <DropdownMenuItem
                                    onClick={() => handleStatusToggle(trainer)}
                                    disabled={statusMutation.isPending}
                                  >
                                    <Power className="mr-2 h-4 w-4" />
                                    {trainer.isActive ? 'Deactivate' : 'Activate'}
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
                    <Users className="mx-auto h-12 w-12 text-muted-foreground mb-4" />
                    <h3 className="text-lg font-semibold mb-2">No trainers found</h3>
                    <p className="text-muted-foreground mb-4">
                      {searchQuery
                        ? 'Try adjusting your search query'
                        : 'Get started by adding your first trainer'}
                    </p>
                    {!searchQuery && (
                      <Button asChild>
                        <Link to="/admin/trainers/create">
                          <Plus className="mr-2 h-4 w-4" />
                          Add Trainer
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

