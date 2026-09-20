import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BookOpen, Briefcase, Mail, Power, Send, UserX } from 'lucide-react'
import { useParams } from 'react-router-dom'

import { resendInvitation } from '@/api/bulk-upload'
import { getTrainerById, updateTrainerStatus } from '@/api/college-admin'
import { apiErrorMessage } from '@/lib/apiError'
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
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/shared/components/ui'
import { useToastNotifications } from '@/shared/hooks/useToastNotifications'

/**
 * One trainer, as their college admin sees them.
 *
 * Reached from "View Details" in the trainers list, which pointed at a route
 * that did not exist. Deliberately modest: everything a college admin can do to
 * a trainer beyond this -- assigning them to a batch -- happens on the batch,
 * and duplicating it here would mean two screens that can disagree.
 */
export function TrainerDetail() {
  const { id } = useParams<{ id: string }>()
  const trainerId = Number(id)
  const queryClient = useQueryClient()
  const { showSuccess, showError } = useToastNotifications()
  const valid = Number.isInteger(trainerId) && trainerId > 0

  const {
    data: trainer,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['admin', 'trainers', trainerId],
    queryFn: () => getTrainerById(trainerId),
    enabled: valid,
  })

  const statusMutation = useMutation({
    mutationFn: (isActive: boolean) => updateTrainerStatus(trainerId, isActive),
    onSuccess: (_, isActive) => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'trainers'] })
      showSuccess(isActive ? 'Trainer activated' : 'Trainer deactivated')
    },
    onError: (err) => showError(apiErrorMessage(err, 'Could not change the status')),
  })

  const resendMutation = useMutation({
    // The user id, not the trainer id in the URL -- `InvitationService.resend`
    // looks the account up by user id even though the path reads
    // `/admin/trainers/{id}`.
    mutationFn: () => resendInvitation('trainers', trainer!.userId),
    onSuccess: () => showSuccess('Invitation sent again'),
    onError: (err) => showError(apiErrorMessage(err, 'Could not resend the invitation')),
  })

  const backTo = { href: '/admin/trainers', label: 'All trainers' }

  return (
    <RoleGuard allowedRoles={['COLLEGE_ADMIN']}>
      <AuthenticatedLayout>
        <PageWrapper>
          {!valid ? (
            <div className="space-y-6">
              <PageHeader title="Trainer" backTo={backTo} />
              <EmptyState
                icon={UserX}
                title="That is not a trainer id"
                description={`"${id}" is not a number, so there is nothing to look up.`}
              />
            </div>
          ) : isLoading ? (
            <DetailSkeleton />
          ) : isNotFound(error) ? (
            <div className="space-y-6">
              <PageHeader title="Trainer" backTo={backTo} />
              <EmptyState
                icon={UserX}
                title="No such trainer"
                description="They may have been removed, or belong to another college."
              />
            </div>
          ) : error || !trainer ? (
            <div className="space-y-6">
              <PageHeader title="Trainer" backTo={backTo} />
              <ErrorState error={error} title="Could not load this trainer" />
            </div>
          ) : (
            <div className="space-y-6">
              <PageHeader
                title={trainer.fullName}
                description={trainer.email}
                backTo={backTo}
                actions={
                  <>
                    <Button
                      variant="outline"
                      onClick={() => resendMutation.mutate()}
                      disabled={resendMutation.isPending}
                    >
                      <Send className="mr-2 h-4 w-4" />
                      Resend invitation
                    </Button>
                    <Button
                      variant={trainer.isActive ? 'outline' : 'default'}
                      onClick={() => statusMutation.mutate(!trainer.isActive)}
                      disabled={statusMutation.isPending}
                    >
                      <Power className="mr-2 h-4 w-4" />
                      {trainer.isActive ? 'Deactivate' : 'Activate'}
                    </Button>
                  </>
                }
              >
                <div className="flex flex-wrap items-center gap-2 pt-1">
                  <Badge variant={trainer.isActive ? 'success' : 'secondary'}>
                    {trainer.isActive ? 'Active' : 'Inactive'}
                  </Badge>
                  {trainer.department && (
                    <Badge variant="outline">{trainer.department}</Badge>
                  )}
                </div>
              </PageHeader>

              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                <StatCard
                  label="Assigned batches"
                  value={trainer.assignedBatchIds?.length ?? 0}
                  hint="Assign on the batch, under Trainers"
                  icon={BookOpen}
                />
                <StatCard
                  label="Specialisation"
                  value={trainer.specialization || '—'}
                  icon={Briefcase}
                />
                <StatCard
                  label="Account"
                  value={trainer.isActive ? 'Active' : 'Inactive'}
                  hint={trainer.email}
                  icon={Mail}
                />
              </div>

              <Card>
                <CardHeader>
                  <CardTitle>Profile</CardTitle>
                  <CardDescription>What the college holds on this trainer.</CardDescription>
                </CardHeader>
                <CardContent className="space-y-6">
                  <DetailList
                    columns={3}
                    items={[
                      { label: 'Full name', value: trainer.fullName },
                      { label: 'Email', value: trainer.email },
                      { label: 'Phone', value: trainer.phone },
                      { label: 'Department', value: trainer.department },
                      { label: 'Specialisation', value: trainer.specialization },
                    ]}
                  />
                  {trainer.bio && (
                    <div>
                      <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        Bio
                      </p>
                      <p className="mt-2 whitespace-pre-line text-sm leading-relaxed">
                        {trainer.bio}
                      </p>
                    </div>
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
