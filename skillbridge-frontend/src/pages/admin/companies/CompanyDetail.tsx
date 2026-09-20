import { useQuery } from '@tanstack/react-query'
import { Building2, Globe, Link2, SearchX } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'

import { getCompanyById } from '@/api/college-admin'
import { RoleGuard } from '@/shared/components/auth'
import { AuthenticatedLayout, PageWrapper } from '@/shared/components/layout'
import {
  DetailList,
  DetailSkeleton,
  EmptyState,
  ErrorState,
  PageHeader,
  StatCard,
  isNotFound,
} from '@/shared/components/page'
import {
  Badge,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/shared/components/ui'

const HIRING_LABEL: Record<string, string> = {
  FULL_TIME: 'Full time',
  INTERNSHIP: 'Internship',
  BOTH: 'Full time and internship',
}

/**
 * One company, as a college or system admin sees it.
 *
 * Reached from "View Details" in the companies list, which pointed at a route
 * that did not exist. Both admin roles can open it, matching
 * `GET /admin/companies/{id}` -- the endpoint is `COLLEGE_ADMIN,SYSTEM_ADMIN`,
 * and a guard narrower than the endpoint is a screen that 403s itself.
 */
export function CompanyDetail() {
  const { id } = useParams<{ id: string }>()
  const companyId = Number(id)
  const valid = Number.isInteger(companyId) && companyId > 0

  const {
    data: company,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['admin', 'companies', companyId],
    queryFn: () => getCompanyById(companyId),
    enabled: valid,
  })

  const backTo = { href: '/admin/companies', label: 'All companies' }
  const linkedBatches = company?.linkedBatchIds ?? []

  return (
    <RoleGuard allowedRoles={['COLLEGE_ADMIN', 'SYSTEM_ADMIN']}>
      <AuthenticatedLayout>
        <PageWrapper>
          {!valid ? (
            <div className="space-y-6">
              <PageHeader title="Company" backTo={backTo} />
              <EmptyState
                icon={SearchX}
                title="That is not a company id"
                description={`"${id}" is not a number, so there is nothing to look up.`}
              />
            </div>
          ) : isLoading ? (
            <DetailSkeleton />
          ) : isNotFound(error) ? (
            <div className="space-y-6">
              <PageHeader title="Company" backTo={backTo} />
              <EmptyState
                icon={SearchX}
                title="No such company"
                description="It may have been removed, or belong to another college."
              />
            </div>
          ) : error || !company ? (
            <div className="space-y-6">
              <PageHeader title="Company" backTo={backTo} />
              <ErrorState error={error} title="Could not load this company" />
            </div>
          ) : (
            <div className="space-y-6">
              <PageHeader
                title={company.name}
                description={company.domain || 'No website on file'}
                backTo={backTo}
              >
                <div className="flex flex-wrap items-center gap-2 pt-1">
                  <Badge variant="accent">
                    {HIRING_LABEL[company.hiringType] ?? company.hiringType}
                  </Badge>
                </div>
              </PageHeader>

              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                <StatCard
                  label="Linked batches"
                  value={linkedBatches.length}
                  hint="Link on the batch, under Companies"
                  icon={Link2}
                />
                <StatCard
                  label="Hiring"
                  value={HIRING_LABEL[company.hiringType] ?? company.hiringType}
                  icon={Building2}
                />
                <StatCard label="Website" value={company.domain || '—'} icon={Globe} />
              </div>

              <Card>
                <CardHeader>
                  <CardTitle>Details</CardTitle>
                  <CardDescription>
                    What the college holds on this company, and how they hire.
                  </CardDescription>
                </CardHeader>
                <CardContent className="space-y-6">
                  <DetailList
                    items={[
                      { label: 'Name', value: company.name },
                      { label: 'Website', value: company.domain },
                      {
                        label: 'Hiring type',
                        value: HIRING_LABEL[company.hiringType] ?? company.hiringType,
                      },
                    ]}
                  />
                  {company.hiringProcess && (
                    <div>
                      <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        Hiring process
                      </p>
                      <p className="mt-2 whitespace-pre-line text-sm leading-relaxed">
                        {company.hiringProcess}
                      </p>
                    </div>
                  )}
                  {company.notes && (
                    <div>
                      <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        Notes
                      </p>
                      <p className="mt-2 whitespace-pre-line text-sm leading-relaxed">
                        {company.notes}
                      </p>
                    </div>
                  )}
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle>Linked batches</CardTitle>
                  <CardDescription>
                    Batches this company is attached to, which is what puts it in front of
                    those students.
                  </CardDescription>
                </CardHeader>
                <CardContent>
                  {linkedBatches.length === 0 ? (
                    <EmptyState
                      icon={Link2}
                      title="Not linked to any batch"
                      description="Open a batch and add this company under its Companies tab."
                      className="py-8"
                    />
                  ) : (
                    <ul className="flex flex-wrap gap-2">
                      {linkedBatches.map((batchId) => (
                        <li key={batchId}>
                          <Link
                            to={`/admin/batches/${batchId}`}
                            className="inline-flex items-center gap-2 rounded-lg border px-3 py-2 text-sm font-medium transition-colors hover:border-primary/40 hover:bg-accent"
                          >
                            <Link2 className="h-4 w-4 text-muted-foreground" />
                            {/* The list endpoint gives ids only; the batch page
                                is one click away and has the name. */}
                            Batch #{batchId}
                          </Link>
                        </li>
                      ))}
                    </ul>
                  )}
                </CardContent>
              </Card>
            </div>
          )}
        </PageWrapper>
      </AuthenticatedLayout>
    </RoleGuard>
  )
}
