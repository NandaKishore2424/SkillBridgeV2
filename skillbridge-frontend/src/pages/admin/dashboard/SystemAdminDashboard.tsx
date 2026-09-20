/**
 * System Admin Dashboard
 * 
 * Overview page with:
 * - Key statistics (total colleges, active colleges, total students, etc.)
 * - Grid of college cards (clickable)
 * - Quick actions (Create College button)
 * - Professional card-based layout
 */

import { Link, useNavigate } from 'react-router-dom'
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
import { StatCardSkeleton, CardSkeleton } from '@/shared/components/ui'
import { getAllColleges } from '@/api/admin'
import type { College } from '@/shared/types'
import { ArrowRight, Building2, CheckCircle2, Plus, Users, XCircle } from 'lucide-react'
import { ErrorState, PageHeader, StatCard } from '@/shared/components/page'
import { itemsOf } from '@/api/paging'

interface CollegeCardProps {
  college: College
  onClick: () => void
}

function CollegeCard({ college, onClick }: CollegeCardProps) {
  return (
    <Card 
      className="hover:shadow-xl transition-all duration-300 cursor-pointer group border hover:border-primary/50"
      onClick={onClick}
    >
      <CardHeader className="pb-3">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-3">
            <div className="h-12 w-12 rounded-lg bg-primary/10 flex items-center justify-center group-hover:bg-primary/20 transition-colors">
              <Building2 className="h-6 w-6 text-primary" />
            </div>
            <div>
              <CardTitle className="text-lg group-hover:text-primary transition-colors">
                {college.name}
              </CardTitle>
              <CardDescription className="font-mono text-xs mt-1">
                {college.code}
              </CardDescription>
            </div>
          </div>
          <Badge
            variant={college.status === 'ACTIVE' ? 'default' : 'secondary'}
            className="ml-auto"
          >
            {college.status === 'ACTIVE' ? (
              <CheckCircle2 className="h-3 w-3 mr-1" />
            ) : (
              <XCircle className="h-3 w-3 mr-1" />
            )}
            {college.status}
          </Badge>
        </div>
      </CardHeader>
      <CardContent>
        <div className="space-y-2">
          {college.email && (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <span className="font-medium">Email:</span>
              <span>{college.email}</span>
            </div>
          )}
          {college.phone && (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <span className="font-medium">Phone:</span>
              <span>{college.phone}</span>
            </div>
          )}
          <div className="pt-2 border-t flex items-center justify-between">
            <span className="text-xs text-muted-foreground">View Details</span>
            <ArrowRight className="h-4 w-4 text-muted-foreground group-hover:text-primary group-hover:translate-x-1 transition-all" />
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

export function SystemAdminDashboard() {
  const navigate = useNavigate()
  const {
    data: colleges,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['admin', 'colleges'],
    queryFn: () => getAllColleges({ size: 100 }),
    select: itemsOf,
  })

  // Calculate statistics
  const totalColleges = colleges?.length || 0
  const activeColleges = colleges?.filter(c => c.status === 'ACTIVE').length || 0
  const inactiveColleges = totalColleges - activeColleges

  const handleCollegeClick = (collegeId: number) => {
    navigate(`/admin/colleges/${collegeId}`)
  }

  return (
    <RoleGuard allowedRoles={['SYSTEM_ADMIN']}>
      <AuthenticatedLayout>
        <PageWrapper>
          <div className="space-y-6">
            <PageHeader
              title="Colleges"
              description="Every college on this installation."
              actions={
                <Button asChild>
                  <Link to="/admin/colleges/create">
                    <Plus className="mr-2 h-4 w-4" />
                    Add a college
                  </Link>
                </Button>
              }
            />

            {error && <ErrorState error={error} title="Could not load the colleges" />}

            {/*
              Two of the four cards that used to be here were invented.

              "Total Students" printed the string "0" -- not a count that
              happened to be zero, a hardcoded zero labelled "Across all
              colleges". `GET /admin/colleges` returns id, name, code, email,
              phone, status and address; there is no student count in it and no
              endpoint that gives one, so the number cannot be shown honestly
              and is not shown at all.

              "System Health: 100% -- All systems operational" measured nothing
              whatsoever. It would have read 100% with the database down. The
              real signal is `/actuator/health`, which is public and not wired
              to this page; showing a green tick that cannot go red is worse
              than showing none.
            */}
            <section aria-label="At a glance" className="grid gap-4 sm:grid-cols-3">
              {isLoading ? (
                <>
                  <StatCardSkeleton />
                  <StatCardSkeleton />
                  <StatCardSkeleton />
                </>
              ) : (
                <>
                  <StatCard label="Colleges" value={totalColleges} icon={Building2} />
                  <StatCard
                    label="Active"
                    value={activeColleges}
                    hint="Can sign in and be administered"
                    icon={CheckCircle2}
                  />
                  <StatCard
                    label="Inactive"
                    value={inactiveColleges}
                    hint="Deactivated; their users cannot sign in"
                    icon={Building2}
                  />
                </>
              )}
            </section>

            {/* Colleges Section */}
            <div>
              <div className="flex items-center justify-between mb-4">
                <div>
                  <h2 className="text-2xl font-semibold tracking-tight">Colleges</h2>
                  <p className="text-sm text-muted-foreground">
                    {totalColleges} {totalColleges === 1 ? 'college' : 'colleges'} registered
                  </p>
                </div>
                <Button variant="outline" asChild>
                  <Link to="/admin/colleges">
                    View All <ArrowRight className="ml-2 h-4 w-4" />
                  </Link>
                </Button>
              </div>

              {isLoading ? (
                <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
                  <CardSkeleton />
                  <CardSkeleton />
                  <CardSkeleton />
                </div>
              ) : colleges && colleges.length > 0 ? (
                <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
                  {colleges.slice(0, 6).map((college) => (
                    <CollegeCard
                      key={college.id}
                      college={college}
                      onClick={() => handleCollegeClick(college.id)}
                    />
                  ))}
                </div>
              ) : (
                <Card className="border-dashed">
                  <CardContent className="flex flex-col items-center justify-center py-12">
                    <Building2 className="h-12 w-12 text-muted-foreground mb-4" />
                    <h3 className="text-lg font-semibold mb-2">No colleges yet</h3>
                    <p className="text-sm text-muted-foreground mb-4 text-center max-w-md">
                      Get started by creating your first college. Colleges are the foundation of the SkillBridge platform.
                    </p>
                    <Button asChild>
                      <Link to="/admin/colleges/create">
                        <Plus className="mr-2 h-4 w-4" />
                        Create Your First College
                      </Link>
                    </Button>
                  </CardContent>
                </Card>
              )}
            </div>

            {/* Quick Actions */}
            <Card className="bg-gradient-to-br from-primary/5 to-primary/10 border-primary/20">
              <CardHeader>
                <CardTitle>Quick Actions</CardTitle>
                <CardDescription>Common administrative tasks</CardDescription>
              </CardHeader>
              <CardContent>
                <div className="grid gap-3 md:grid-cols-3">
                  <Button asChild variant="outline" className="justify-start h-auto py-3">
                    <Link to="/admin/colleges/create">
                      <Plus className="mr-2 h-4 w-4" />
                      <div className="text-left">
                        <div className="font-medium">Create College</div>
                        <div className="text-xs text-muted-foreground">Add a new college</div>
                      </div>
                    </Link>
                  </Button>
                  <Button asChild variant="outline" className="justify-start h-auto py-3">
                    <Link to="/admin/colleges">
                      <Building2 className="mr-2 h-4 w-4" />
                      <div className="text-left">
                        <div className="font-medium">Manage Colleges</div>
                        <div className="text-xs text-muted-foreground">View all colleges</div>
                      </div>
                    </Link>
                  </Button>
                  <Button asChild variant="outline" className="justify-start h-auto py-3">
                    <Link to="/admin/companies">
                      <Users className="mr-2 h-4 w-4" />
                      <div className="text-left">
                        <div className="font-medium">View Companies</div>
                        <div className="text-xs text-muted-foreground">System-wide companies</div>
                      </div>
                    </Link>
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        </PageWrapper>
      </AuthenticatedLayout>
    </RoleGuard>
  )
}

