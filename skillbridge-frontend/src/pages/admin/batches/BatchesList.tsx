/**
 * Batches List Page
 * 
 * College Admin page to view and manage all batches
 * Features:
 * - Table with all batches
 * - Search/filter functionality
 * - Status badges
 * - Actions: View, Edit, Update Status
 * - Create batch button
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
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/shared/components/ui'
import { TableSkeleton } from '@/shared/components/ui/loading-skeleton'
import {
  getBatches,
  updateBatchStatus,
  type BatchWithDetails,
  type BatchStatus,
} from '@/api/college-admin'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import {
  Plus,
  Search,
  BookOpen,
  MoreVertical,
  Edit,
  Loader2,
  AlertCircle,
  Users,
  Briefcase,
} from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/shared/components/ui'

const STATUS_COLORS: Record<BatchStatus, 'default' | 'secondary' | 'outline'> = {
  UPCOMING: 'outline',
  OPEN: 'default',
  ACTIVE: 'default',
  COMPLETED: 'secondary',
  CANCELLED: 'secondary',
}

export function BatchesList() {
  const queryClient = useQueryClient()
  const location = useLocation()
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<BatchStatus | 'ALL'>('ALL')
  const { showSuccess, showError } = useToastNotifications()

  // Show success message from navigation state
  useEffect(() => {
    if (location.state?.message) {
      showSuccess(location.state.message)
      window.history.replaceState({}, document.title)
    }
  }, [location.state, showSuccess])

  // Fetch batches
  const {
    data: batches,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['admin', 'batches'],
    queryFn: getBatches,
  })

  // Update batch status mutation
  const statusMutation = useMutation({
    mutationFn: ({ id, status }: { id: number; status: BatchStatus }) =>
      updateBatchStatus(id, status),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'batches'] })
      showSuccess(`Batch status updated to ${variables.status}`)
    },
    onError: (error: any) => {
      showError(error?.response?.data?.message || 'Failed to update batch status')
    },
  })

  // Filter batches
  const filteredBatches = batches?.filter((batch) => {
    const matchesSearch =
      !searchQuery ||
      batch.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      batch.description?.toLowerCase().includes(searchQuery.toLowerCase())
    const matchesStatus = statusFilter === 'ALL' || batch.status === statusFilter
    return matchesSearch && matchesStatus
  })

  const handleStatusChange = (batch: BatchWithDetails, newStatus: BatchStatus) => {
    if (
      confirm(
        `Are you sure you want to change batch "${batch.name}" status to ${newStatus}?`
      )
    ) {
      statusMutation.mutate({ id: batch.id, status: newStatus })
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
                <h1 className="text-3xl font-bold tracking-tight">Batches</h1>
                <p className="text-muted-foreground">
                  Manage training batches for your college
                </p>
              </div>
              <Button asChild>
                <Link to="/admin/batches/create">
                  <Plus className="mr-2 h-4 w-4" />
                  Create Batch
                </Link>
              </Button>
            </div>

            {/* Filters */}
            <Card>
              <CardContent className="pt-6">
                <div className="flex flex-col sm:flex-row gap-4">
                  <div className="relative flex-1">
                    <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                    <Input
                      placeholder="Search batches by name or description..."
                      value={searchQuery}
                      onChange={(e) => setSearchQuery(e.target.value)}
                      className="pl-10"
                    />
                  </div>
                  <Select value={statusFilter} onValueChange={(v) => setStatusFilter(v as any)}>
                    <SelectTrigger className="w-full sm:w-[180px]">
                      <SelectValue placeholder="Filter by status" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="ALL">All Statuses</SelectItem>
                      <SelectItem value="UPCOMING">Upcoming</SelectItem>
                      <SelectItem value="OPEN">Open</SelectItem>
                      <SelectItem value="ACTIVE">Active</SelectItem>
                      <SelectItem value="COMPLETED">Completed</SelectItem>
                      <SelectItem value="CANCELLED">Cancelled</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              </CardContent>
            </Card>

            {/* Error Alert */}
            {error && (
              <Alert variant="destructive">
                <AlertCircle className="h-4 w-4" />
                <AlertDescription>
                  Failed to load batches. Please try again.
                </AlertDescription>
              </Alert>
            )}

            {/* Batches Table */}
            <Card>
              <CardHeader>
                <CardTitle>All Batches</CardTitle>
                <CardDescription>
                  {filteredBatches?.length || 0} batch(es) found
                </CardDescription>
              </CardHeader>
              <CardContent>
                {isLoading ? (
                  <TableSkeleton rows={5} columns={7} />
                ) : filteredBatches && filteredBatches.length > 0 ? (
                  <div className="rounded-md border">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>Name</TableHead>
                          <TableHead>Status</TableHead>
                          <TableHead>Students</TableHead>
                          <TableHead>Trainers</TableHead>
                          <TableHead>Companies</TableHead>
                          <TableHead>Dates</TableHead>
                          <TableHead className="text-right">Actions</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {filteredBatches.map((batch) => (
                          <TableRow key={batch.id}>
                            <TableCell className="font-medium">
                              <div className="flex items-center gap-2">
                                <BookOpen className="h-4 w-4 text-muted-foreground" />
                                <div>
                                  <div>{batch.name}</div>
                                  {batch.description && (
                                    <div className="text-xs text-muted-foreground truncate max-w-md">
                                      {batch.description}
                                    </div>
                                  )}
                                </div>
                              </div>
                            </TableCell>
                            <TableCell>
                              <Badge variant={STATUS_COLORS[batch.status]}>
                                {batch.status}
                              </Badge>
                            </TableCell>
                            <TableCell>
                              <div className="flex items-center gap-1">
                                <Users className="h-3 w-3 text-muted-foreground" />
                                {batch.enrolledCount || 0}
                              </div>
                            </TableCell>
                            <TableCell>
                              <div className="flex items-center gap-1">
                                <Users className="h-3 w-3 text-muted-foreground" />
                                {batch.trainerCount || 0}
                              </div>
                            </TableCell>
                            <TableCell>
                              <div className="flex items-center gap-1">
                                <Briefcase className="h-3 w-3 text-muted-foreground" />
                                {batch.companyCount || 0}
                              </div>
                            </TableCell>
                            <TableCell>
                              {batch.startDate && batch.endDate ? (
                                <div className="text-sm">
                                  <div>{new Date(batch.startDate).toLocaleDateString()}</div>
                                  <div className="text-xs text-muted-foreground">
                                    to {new Date(batch.endDate).toLocaleDateString()}
                                  </div>
                                </div>
                              ) : (
                                <span className="text-muted-foreground">-</span>
                              )}
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
                                    <Link to={`/admin/batches/${batch.id}`} className="flex items-center">
                                      <Edit className="mr-2 h-4 w-4" />
                                      View Details
                                    </Link>
                                  </DropdownMenuItem>
                                  {batch.status !== 'COMPLETED' && batch.status !== 'CANCELLED' && (
                                    <DropdownMenuItem
                                      onClick={() => {
                                        const nextStatus: BatchStatus =
                                          batch.status === 'UPCOMING'
                                            ? 'OPEN'
                                            : batch.status === 'OPEN'
                                              ? 'ACTIVE'
                                              : 'COMPLETED'
                                        handleStatusChange(batch, nextStatus)
                                      }}
                                      disabled={statusMutation.isPending}
                                    >
                                      Update Status
                                    </DropdownMenuItem>
                                  )}
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
                    <BookOpen className="mx-auto h-12 w-12 text-muted-foreground mb-4" />
                    <h3 className="text-lg font-semibold mb-2">No batches found</h3>
                    <p className="text-muted-foreground mb-4">
                      {searchQuery || statusFilter !== 'ALL'
                        ? 'Try adjusting your filters'
                        : 'Get started by creating your first batch'}
                    </p>
                    {!searchQuery && statusFilter === 'ALL' && (
                      <Button asChild>
                        <Link to="/admin/batches/create">
                          <Plus className="mr-2 h-4 w-4" />
                          Create Batch
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

