/**
 * Trainer Students Page
 * 
 * Shows all students across all batches assigned to the trainer
 */

import { useQuery } from '@tanstack/react-query'
import { AuthenticatedLayout, PageWrapper } from '@/shared/components/layout'
import { RoleGuard } from '@/shared/components/auth'
import {
    Card,
    CardContent,
    CardDescription,
    CardHeader,
    CardTitle,
    Alert,
    AlertDescription,
} from '@/shared/components/ui'
import { ListSkeleton } from '@/shared/components/ui/loading-skeleton'
import { getTrainerBatches } from '@/api/trainer'
import { AlertCircle, Users } from 'lucide-react'

export function TrainerStudentsPage() {
    const {
        data: batches,
        isLoading,
        error,
    } = useQuery({
        queryKey: ['trainer', 'batches'],
        queryFn: getTrainerBatches,
    })

    // Calculate total students across all batches
    const totalStudents = batches?.reduce((sum, batch) => sum + (batch.enrolledCount || 0), 0) || 0

    return (
        <RoleGuard allowedRoles={['TRAINER']}>
            <AuthenticatedLayout>
                <PageWrapper>
                    <div className="space-y-6">
                        {/* Header */}
                        <div>
                            <h1 className="text-3xl font-bold tracking-tight">My Students</h1>
                            <p className="text-muted-foreground">
                                View and track all students across your assigned batches
                            </p>
                        </div>

                        {/* Error Alert */}
                        {error && (
                            <Alert variant="destructive">
                                <AlertCircle className="h-4 w-4" />
                                <AlertDescription>
                                    Failed to load student data. Please try again.
                                </AlertDescription>
                            </Alert>
                        )}

                        {/* Stats Card */}
                        <Card>
                            <CardHeader>
                                <CardTitle className="flex items-center gap-2">
                                    <Users className="h-5 w-5" />
                                    Total Students
                                </CardTitle>
                                <CardDescription>
                                    Students enrolled across all your assigned batches
                                </CardDescription>
                            </CardHeader>
                            <CardContent>
                                {isLoading ? (
                                    <ListSkeleton items={1} />
                                ) : (
                                    <div className="text-4xl font-bold">{totalStudents}</div>
                                )}
                            </CardContent>
                        </Card>

                        {/* Students by Batch */}
                        <Card>
                            <CardHeader>
                                <CardTitle>Students by Batch</CardTitle>
                                <CardDescription>
                                    View students organized by their assigned batches
                                </CardDescription>
                            </CardHeader>
                            <CardContent>
                                {isLoading ? (
                                    <ListSkeleton items={3} />
                                ) : batches && batches.length > 0 ? (
                                    <div className="space-y-4">
                                        {batches.map((batch) => (
                                            <Card key={batch.id}>
                                                <CardContent className="pt-6">
                                                    <div className="flex items-center justify-between">
                                                        <div>
                                                            <h3 className="font-semibold">{batch.name}</h3>
                                                            <p className="text-sm text-muted-foreground">
                                                                {batch.enrolledCount || 0} student{batch.enrolledCount !== 1 ? 's' : ''}
                                                            </p>
                                                        </div>
                                                        {/* TODO: Add link to view batch students */}
                                                    </div>
                                                </CardContent>
                                            </Card>
                                        ))}
                                    </div>
                                ) : (
                                    <div className="text-center py-12">
                                        <Users className="mx-auto h-12 w-12 text-muted-foreground mb-4" />
                                        <h3 className="text-lg font-semibold mb-2">No students found</h3>
                                        <p className="text-muted-foreground">
                                            Students will appear here once batches are assigned to you
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
