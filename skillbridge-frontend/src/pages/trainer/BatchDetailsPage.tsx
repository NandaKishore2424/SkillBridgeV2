import { useParams, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/components/ui/tabs';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/components/ui/card';
import { Button } from '@/shared/components/ui/button';
import { ArrowLeft, BookOpen, Calendar, Users } from 'lucide-react';
import { batchApi } from '@/api/batchManagement';
import SyllabusTab from '@/components/batch-management/SyllabusTab';
import TimelineTab from '@/components/batch-management/TimelineTab';
import StudentsTab from '@/components/batch-management/StudentsTab';

export default function BatchDetailsPage() {
    const { id } = useParams<{ id: string }>();
    const navigate = useNavigate();
    const batchId = parseInt(id || '0');

    const { data: batch, isLoading } = useQuery({
        queryKey: ['batch', batchId],
        queryFn: async () => {
            const response = await batchApi.getBatchById(batchId);
            return response.data;
        },
    });

    if (isLoading) {
        return (
            <div className="flex items-center justify-center min-h-screen">
                <div className="text-center">
                    <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-primary mx-auto mb-4"></div>
                    <p className="text-muted-foreground">Loading batch details...</p>
                </div>
            </div>
        );
    }

    if (!batch) {
        return (
            <div className="container mx-auto py-8">
                <Card>
                    <CardContent className="pt-6">
                        <p className="text-center text-muted-foreground">Batch not found</p>
                    </CardContent>
                </Card>
            </div>
        );
    }

    return (
        <div className="container mx-auto py-6 space-y-6">
            {/* Header */}
            <div className="flex items-center gap-4">
                <Button
                    variant="ghost"
                    size="icon"
                    onClick={() => navigate('/trainer/batches')}
                >
                    <ArrowLeft className="h-5 w-5" />
                </Button>
                <div className="flex-1">
                    <h1 className="text-3xl font-bold">{batch.name}</h1>
                    <p className="text-muted-foreground mt-1">
                        {batch.description || 'Manage syllabus, timeline, and students'}
                    </p>
                </div>
            </div>

            {/* Batch Overview Card */}
            <Card>
                <CardHeader>
                    <CardTitle>Batch Overview</CardTitle>
                    <CardDescription>Quick summary of batch information</CardDescription>
                </CardHeader>
                <CardContent>
                    <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                        <div className="flex items-center gap-3">
                            <div className="p-2 bg-primary/10 rounded-lg">
                                <Calendar className="h-5 w-5 text-primary" />
                            </div>
                            <div>
                                <p className="text-sm text-muted-foreground">Duration</p>
                                <p className="font-medium">
                                    {new Date(batch.startDate).toLocaleDateString()} - {new Date(batch.endDate).toLocaleDateString()}
                                </p>
                            </div>
                        </div>
                        <div className="flex items-center gap-3">
                            <div className="p-2 bg-primary/10 rounded-lg">
                                <Users className="h-5 w-5 text-primary" />
                            </div>
                            <div>
                                <p className="text-sm text-muted-foreground">Students Enrolled</p>
                                <p className="font-medium">{batch.studentCount || 0} students</p>
                            </div>
                        </div>
                        <div className="flex items-center gap-3">
                            <div className="p-2 bg-primary/10 rounded-lg">
                                <BookOpen className="h-5 w-5 text-primary" />
                            </div>
                            <div>
                                <p className="text-sm text-muted-foreground">Status</p>
                                <p className="font-medium capitalize">{batch.status || 'Active'}</p>
                            </div>
                        </div>
                    </div>
                </CardContent>
            </Card>

            {/* Tabs */}
            <Tabs defaultValue="syllabus" className="space-y-4">
                <TabsList className="grid w-full grid-cols-3 lg:w-auto">
                    <TabsTrigger value="syllabus" className="gap-2">
                        <BookOpen className="h-4 w-4" />
                        Syllabus
                    </TabsTrigger>
                    <TabsTrigger value="timeline" className="gap-2">
                        <Calendar className="h-4 w-4" />
                        Timeline
                    </TabsTrigger>
                    <TabsTrigger value="students" className="gap-2">
                        <Users className="h-4 w-4" />
                        Students
                    </TabsTrigger>
                </TabsList>

                <TabsContent value="syllabus">
                    <SyllabusTab batchId={batchId} />
                </TabsContent>

                <TabsContent value="timeline">
                    <TimelineTab batchId={batchId} />
                </TabsContent>

                <TabsContent value="students">
                    <StudentsTab batchId={batchId} />
                </TabsContent>
            </Tabs>
        </div>
    );
}
