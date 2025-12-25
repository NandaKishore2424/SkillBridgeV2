/**
 * Companies List Page
 * 
 * College Admin page to view and manage all companies
 * Features:
 * - Table with all companies
 * - Search functionality
 * - Actions: View, Edit
 * - Create company button
 */

import { useState, useEffect } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
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
import { getCompanies, type Company } from '@/api/college-admin'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'
import { Plus, Search, Briefcase, MoreVertical, Edit, Loader2, AlertCircle } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/shared/components/ui'

const HIRING_TYPE_LABELS: Record<Company['hiringType'], string> = {
  FULL_TIME: 'Full Time',
  INTERNSHIP: 'Internship',
  BOTH: 'Both',
}

export function CompaniesList() {
  const location = useLocation()
  const [searchQuery, setSearchQuery] = useState('')
  const { showSuccess } = useToastNotifications()

  // Show success message from navigation state
  useEffect(() => {
    if (location.state?.message) {
      showSuccess(location.state.message)
      window.history.replaceState({}, document.title)
    }
  }, [location.state, showSuccess])

  // Fetch companies
  const {
    data: companies,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['admin', 'companies'],
    queryFn: getCompanies,
  })

  // Filter companies
  const filteredCompanies = companies?.filter((company) => {
    if (!searchQuery) return true
    const query = searchQuery.toLowerCase()
    return (
      company.name.toLowerCase().includes(query) ||
      company.domain?.toLowerCase().includes(query)
    )
  })

  return (
    <RoleGuard allowedRoles={['COLLEGE_ADMIN']}>
      <AuthenticatedLayout>
        <PageWrapper>
          <div className="space-y-6">
            {/* Header */}
            <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
              <div>
                <h1 className="text-3xl font-bold tracking-tight">Companies</h1>
                <p className="text-muted-foreground">
                  Manage companies linked to your college
                </p>
              </div>
              <Button asChild>
                <Link to="/admin/companies/create">
                  <Plus className="mr-2 h-4 w-4" />
                  Add Company
                </Link>
              </Button>
            </div>

            {/* Search */}
            <Card>
              <CardContent className="pt-6">
                <div className="relative">
                  <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                  <Input
                    placeholder="Search companies by name or domain..."
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
                  Failed to load companies. Please try again.
                </AlertDescription>
              </Alert>
            )}

            {/* Companies Table */}
            <Card>
              <CardHeader>
                <CardTitle>All Companies</CardTitle>
                <CardDescription>
                  {filteredCompanies?.length || 0} company(ies) found
                </CardDescription>
              </CardHeader>
              <CardContent>
                {isLoading ? (
                  <TableSkeleton rows={5} columns={5} />
                ) : filteredCompanies && filteredCompanies.length > 0 ? (
                  <div className="rounded-md border">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>Name</TableHead>
                          <TableHead>Domain</TableHead>
                          <TableHead>Hiring Type</TableHead>
                          <TableHead>Linked Batches</TableHead>
                          <TableHead className="text-right">Actions</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {filteredCompanies.map((company) => (
                          <TableRow key={company.id}>
                            <TableCell className="font-medium">
                              <div className="flex items-center gap-2">
                                <Briefcase className="h-4 w-4 text-muted-foreground" />
                                {company.name}
                              </div>
                            </TableCell>
                            <TableCell>
                              {company.domain ? (
                                <a
                                  href={`https://${company.domain}`}
                                  target="_blank"
                                  rel="noopener noreferrer"
                                  className="text-blue-600 hover:underline"
                                >
                                  {company.domain}
                                </a>
                              ) : (
                                <span className="text-muted-foreground">-</span>
                              )}
                            </TableCell>
                            <TableCell>
                              <Badge variant="outline">
                                {HIRING_TYPE_LABELS[company.hiringType]}
                              </Badge>
                            </TableCell>
                            <TableCell>
                              {company.linkedBatchIds && company.linkedBatchIds.length > 0 ? (
                                <span className="text-sm">
                                  {company.linkedBatchIds.length} batch(es)
                                </span>
                              ) : (
                                <span className="text-muted-foreground">None</span>
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
                                    <Link to={`/admin/companies/${company.id}`}>
                                      <Edit className="mr-2 h-4 w-4" />
                                      View Details
                                    </Link>
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
                    <Briefcase className="mx-auto h-12 w-12 text-muted-foreground mb-4" />
                    <h3 className="text-lg font-semibold mb-2">No companies found</h3>
                    <p className="text-muted-foreground mb-4">
                      {searchQuery
                        ? 'Try adjusting your search query'
                        : 'Get started by adding your first company'}
                    </p>
                    {!searchQuery && (
                      <Button asChild>
                        <Link to="/admin/companies/create">
                          <Plus className="mr-2 h-4 w-4" />
                          Add Company
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

