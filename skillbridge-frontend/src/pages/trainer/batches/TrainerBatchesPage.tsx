/**
 * Trainer Batches Page
 * 
 * Shows all batches assigned to the trainer with ability to filter and search
 */

import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { AuthenticatedLayout, PageWrapper } from '@/shared/components/layout'
import { RoleGuard } from '@/shared/components/auth'
import {
    Card,
    CardContent,
    CardDescription,
    CardHeader,
    CardTitle,
    Badge,
    Button,
    Alert,
    AlertDescription,
} from '@/shared/components/ui'
import { ListSkeleton } from '@/shared/components/ui/loading-skeleton'
import { getTrainerBatches } from '@/api/trainer'
import {
    BookOpen,
    GraduationCap,
    AlertCircle,
    ArrowRight,
} from 'lucide-react'

const STATUS_COLORS: Record<string, 'default' | 'secondary' | 'outline'> = {
    UPCOMING: 'outline',
    OPEN: 'default',
    ACTIVE: 'default',
    COMPLETED: 'secondary',
    CANCELLED: 'secondary',
}

export function TrainerBatchesPage() {
    const {
        data: batches,
        isLoading,
        error,
    } = useQuery({
        queryKey: ['trainer', 'batches'],
        queryFn: getTrainerBatches,
    })

    return (
        <RoleGuard allowedRoles={['TRAINER']}>
            <AuthenticatedLayout>
                <PageWrapper>
                    <div className="space-y-6">
                        {/* Header */}
                        <div>
                            <h1 className="text-3xl font-bold tracking-tight">My Batches</h1>
                            <p className="text-muted-foreground">
                                View and manage all batches assigned to you
                            </p>
                        </div>

                        {/* Error Alert */}
                        {error && (
                            <Alert variant="destructive">
                                <AlertCircle className="h-4 w-4" />
                                <AlertDescription>
                                    Failed to load batches. Please try again.
                                </AlertDescription>
                            </Alert>
                        )}

                        {/* Batches List */}
                        {isLoading ? (
                            <ListSkeleton items={3} />
                        ) : batches && batches.length > 0 ? (
                            <div className="grid gap-4">
                                {batches.map((batch) => (
                                    <Card key={batch.id} className="hover:shadow-md transition-shadow">
                                        <CardHeader>
                                            <div className="flex items-start justify-between">
                                                <div className="flex-1">
                                                    <div className="flex items-center gap-3 mb-2">
                                                        <CardTitle>{batch.name}</CardTitle>
                                                        <Badge variant={STATUS_COLORS[batch.status]}>
                                                            {batch.status}
                                                        </Badge>
                                                    </div>
                                                    {batch.description && (
                                                        <CardDescription>{batch.description}</CardDescription>
                                                    )}
                                                </div>
                                                <Button asChild variant="outline">
                                                    <Link to={`/trainer/batches/${batch.id}`}>
                                                        View Details
                                                        <ArrowRight className="ml-2 h-4 w-4" />
                                                    </Link>
                                                </Button>
                                            </div>
                                        </CardHeader>
                                        <CardContent>
                                            <div className="flex items-center gap-6 text-sm text-muted-foreground">
                                                <div className="flex items-center gap-2">
                                                    <GraduationCap className="h-4 w-4" />
                                                    {batch.enrolledCount || 0} student{batch.enrolledCount !== 1 ? 's' : ''}
                                                </div>
                                                {batch.syllabus && (
                                                    <div className="flex items-center gap-2">
                                                        <BookOpen className="h-4 w-4" />
                                                        {batch.syllabus.topicCount} topics
                                                    </div>
                                                )}
                                                {batch.startDate && batch.endDate && (
                                                    <div>
                                                        {new Date(batch.startDate).toLocaleDateString()} -{' '}
                                                        {new Date(batch.endDate).toLocaleDateString()}
                                                    </div>
                                                )}
                                            </div>
                                        </CardContent>
                                    </Card>
                                ))}
                            </div>
                        ) : (
                            <Card>
                                <CardContent className="text-center py-12">
                                    <BookOpen className="mx-auto h-12 w-12 text-muted-foreground mb-4" />
                                    <CardTitle className="mb-2">No batches assigned</CardTitle>
                                    <CardDescription>
                                        You will see batches here once they are assigned to you
                                    </CardDescription>
                                </CardContent>
                            </Card>
                        )}
                    </div>
                </PageWrapper>
            </AuthenticatedLayout>
        </RoleGuard>
    )
}
