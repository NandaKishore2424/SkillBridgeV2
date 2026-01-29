import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/components/ui/card';
import { Button } from '@/shared/components/ui/button';
import { Plus, Copy, Loader2, BookOpen } from 'lucide-react';
import { syllabusApi, type SyllabusModule } from '@/api/batchManagement';
import { useToast } from '@/shared/hooks/use-toast';
import ModuleAccordion from './ModuleAccordion';
import CreateModuleDialog from './CreateModuleDialog';
import CopySyllabusDialog from './CopySyllabusDialog';

interface SyllabusTabProps {
    batchId: number;
}

export default function SyllabusTab({ batchId }: SyllabusTabProps) {
    const { toast } = useToast();
    const queryClient = useQueryClient();
    const [isCreateOpen, setIsCreateOpen] = useState(false);
    const [isCopyOpen, setIsCopyOpen] = useState(false);

    const { data: modules = [], isLoading } = useQuery({
        queryKey: ['syllabus', batchId],
        queryFn: async () => {
            const response = await syllabusApi.getSyllabus(batchId);
            return response.data as SyllabusModule[];
        },
    });

    const deleteMutation = useMutation({
        mutationFn: syllabusApi.deleteModule,
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: ['syllabus', batchId] });
            toast({
                title: 'Module deleted',
                description: 'The module has been successfully deleted.',
            });
        },
        onError: () => {
            toast({
                title: 'Error',
                description: 'Failed to delete module. Please try again.',
                variant: 'destructive',
            });
        },
    });

    const totalTopics = modules.reduce((sum, module) => sum + module.topicsCount, 0);
    const completedTopics = modules.reduce((sum, module) => sum + module.completedTopicsCount, 0);
    const progressPercentage = totalTopics > 0 ? Math.round((completedTopics / totalTopics) * 100) : 0;

    if (isLoading) {
        return (
            <Card>
                <CardContent className="flex items-center justify-center py-12">
                    <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
                </CardContent>
            </Card>
        );
    }

    return (
        <div className="space-y-4">
            {/* Header */}
            <Card>
                <CardHeader>
                    <div className="flex items-center justify-between">
                        <div>
                            <CardTitle>Syllabus</CardTitle>
                            <CardDescription>
                                Manage modules and topics for this batch
                            </CardDescription>
                        </div>
                        <div className="flex gap-2">
                            <Button
                                variant="outline"
                                onClick={() => setIsCopyOpen(true)}
                                disabled={modules.length > 0}
                            >
                                <Copy className="h-4 w-4 mr-2" />
                                Copy from Batch
                            </Button>
                            <Button onClick={() => setIsCreateOpen(true)}>
                                <Plus className="h-4 w-4 mr-2" />
                                Add Module
                            </Button>
                        </div>
                    </div>
                </CardHeader>
                {modules.length > 0 && (
                    <CardContent>
                        <div className="space-y-2">
                            <div className="flex justify-between text-sm">
                                <span className="text-muted-foreground">Overall Progress</span>
                                <span className="font-medium">
                                    {completedTopics} / {totalTopics} topics completed ({progressPercentage}%)
                                </span>
                            </div>
                            <div className="w-full bg-secondary rounded-full h-2">
                                <div
                                    className="bg-primary h-2 rounded-full transition-all duration-300"
                                    style={{ width: `${progressPercentage}%` }}
                                />
                            </div>
                        </div>
                    </CardContent>
                )}
            </Card>

            {/* Modules List */}
            {modules.length === 0 ? (
                <Card>
                    <CardContent className="flex flex-col items-center justify-center py-12 text-center">
                        <BookOpen className="h-12 w-12 text-muted-foreground mb-4" />
                        <h3 className="text-lg font-semibold mb-2">No syllabus yet</h3>
                        <p className="text-muted-foreground mb-4 max-w-sm">
                            Get started by creating your first module or copying from an existing batch.
                        </p>
                        <div className="flex gap-2">
                            <Button variant="outline" onClick={() => setIsCopyOpen(true)}>
                                <Copy className="h-4 w-4 mr-2" />
                                Copy from Batch
                            </Button>
                            <Button onClick={() => setIsCreateOpen(true)}>
                                <Plus className="h-4 w-4 mr-2" />
                                Create Module
                            </Button>
                        </div>
                    </CardContent>
                </Card>
            ) : (
                <div className="space-y-4">
                    {modules.map((module) => (
                        <ModuleAccordion
                            key={module.id}
                            module={module}
                            batchId={batchId}
                            onDelete={() => deleteMutation.mutate(module.id)}
                        />
                    ))}
                </div>
            )}

            {/* Dialogs */}
            <CreateModuleDialog
                batchId={batchId}
                open={isCreateOpen}
                onOpenChange={setIsCreateOpen}
            />

            <CopySyllabusDialog
                targetBatchId={batchId}
                open={isCopyOpen}
                onOpenChange={setIsCopyOpen}
            />
        </div>
    );
}
