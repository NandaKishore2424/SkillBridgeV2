import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  BookOpen,
  GraduationCap,
  Mail,
  Power,
  Send,
  Sparkles,
  UserX,
} from 'lucide-react'
import { useParams } from 'react-router-dom'

import { getStudentById, updateStudentStatus } from '@/api/college-admin'
import { resendInvitation } from '@/api/bulk-upload'
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
import { StudentSkillGap } from '@/shared/components/skill-gap/SkillGapCard'
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
 * One student, as their college admin sees them.
 *
 * This route is the reason the rule in `shared/rules/routes.test.ts` exists.
 * "View Details" in the students list has pointed at `/admin/students/:id`
 * since Phase 2 with no route behind it, so every click quietly returned the
 * admin to the landing page. It was recorded as a known defect and stayed one
 * because nothing failed.
 *
 * The skill-gap report is on the page rather than in a dialog: the dialog in
 * the list is for a quick look while scanning, and this is the screen you open
 * when the answer matters.
 */
export function StudentDetail() {
  const { id } = useParams<{ id: string }>()
  const studentId = Number(id)
  const queryClient = useQueryClient()
  const { showSuccess, showError } = useToastNotifications()

  const {
    data: student,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['admin', 'students', studentId],
    queryFn: () => getStudentById(studentId),
    // `/admin/students/abc` is a typo, not a request worth making.
    enabled: Number.isInteger(studentId) && studentId > 0,
  })

  const statusMutation = useMutation({
    mutationFn: (isActive: boolean) => updateStudentStatus(studentId, isActive),
    onSuccess: (_, isActive) => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'students'] })
      showSuccess(isActive ? 'Student activated' : 'Student deactivated')
    },
    onError: (err) => showError(apiErrorMessage(err, 'Could not change the status')),
  })

  const resendMutation = useMutation({
    // A **user** id, not the student id in the URL. The endpoint is
    // `POST /admin/students/{id}/resend-invitation`, which reads as a student
    // id and is not one: `InvitationService.resend` looks the account up by
    // user id. Passing `studentId` here would reissue a different person's
    // invitation whenever the two numbers happen to differ.
    mutationFn: () => resendInvitation('students', student!.userId),
    onSuccess: () => showSuccess('Invitation sent again'),
    onError: (err) => showError(apiErrorMessage(err, 'Could not resend the invitation')),
  })

  const backTo = { href: '/admin/students', label: 'All students' }

  return (
    <RoleGuard allowedRoles={['COLLEGE_ADMIN']}>
      <AuthenticatedLayout>
        <PageWrapper>
          {!Number.isInteger(studentId) || studentId <= 0 ? (
            <div className="space-y-6">
              <PageHeader title="Student" backTo={backTo} />
              <EmptyState
                icon={UserX}
                title="That is not a student id"
                description={`"${id}" is not a number, so there is nothing to look up.`}
              />
            </div>
          ) : isLoading ? (
            <DetailSkeleton />
          ) : isNotFound(error) ? (
            <div className="space-y-6">
              <PageHeader title="Student" backTo={backTo} />
              <EmptyState
                icon={UserX}
                title="No such student"
                description="They may have been removed, or belong to another college."
              />
            </div>
          ) : error || !student ? (
            <div className="space-y-6">
              <PageHeader title="Student" backTo={backTo} />
              <ErrorState error={error} title="Could not load this student" />
            </div>
          ) : (
            <div className="space-y-6">
              <PageHeader
                title={student.fullName}
                description={student.email}
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
                      variant={student.isActive ? 'outline' : 'default'}
                      onClick={() => statusMutation.mutate(!student.isActive)}
                      disabled={statusMutation.isPending}
                    >
                      <Power className="mr-2 h-4 w-4" />
                      {student.isActive ? 'Deactivate' : 'Activate'}
                    </Button>
                  </>
                }
              >
                <div className="flex flex-wrap items-center gap-2 pt-1">
                  <Badge variant={student.isActive ? 'success' : 'secondary'}>
                    {student.isActive ? 'Active' : 'Inactive'}
                  </Badge>
                  <Badge variant="outline" className="font-mono">
                    {student.rollNumber}
                  </Badge>
                </div>
              </PageHeader>

              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                <StatCard
                  label="Enrolled batches"
                  value={student.enrolledBatchIds?.length ?? 0}
                  icon={BookOpen}
                />
                <StatCard
                  label="Year"
                  value={student.year ?? '—'}
                  hint={student.degree ?? undefined}
                  icon={GraduationCap}
                />
                <StatCard
                  label="Account"
                  value={student.isActive ? 'Active' : 'Inactive'}
                  hint={student.email}
                  icon={Mail}
                />
              </div>

              <Card>
                <CardHeader>
                  <CardTitle>Profile</CardTitle>
                  <CardDescription>
                    What the college holds on this student. Imported from the CSV, then
                    completed by the student at first login.
                  </CardDescription>
                </CardHeader>
                <CardContent>
                  <DetailList
                    columns={3}
                    items={[
                      { label: 'Full name', value: student.fullName },
                      { label: 'Email', value: student.email },
                      { label: 'Roll number', value: student.rollNumber },
                      { label: 'Degree', value: student.degree },
                      { label: 'Branch', value: student.branch },
                      { label: 'Year', value: student.year },
                    ]}
                  />
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2">
                    <Sparkles className="h-4 w-4" />
                    Skill gap
                  </CardTitle>
                  <CardDescription>
                    This student&rsquo;s skills against the industry job descriptions closest to
                    their profile. It runs when their skills change.
                  </CardDescription>
                </CardHeader>
                <CardContent>
                  <StudentSkillGap studentId={studentId} />
                </CardContent>
              </Card>
            </div>
          )}
        </PageWrapper>
      </AuthenticatedLayout>
    </RoleGuard>
  )
}
