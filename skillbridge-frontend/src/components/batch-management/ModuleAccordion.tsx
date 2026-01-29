import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/shared/components/ui/accordion';
import { Button } from '@/shared/components/ui/button';
import { Checkbox } from '@/shared/components/ui/checkbox';
import { Badge } from '@/shared/components/ui/badge';
import { Pencil, Trash2, Plus } from 'lucide-react';
import { syllabusApi, type SyllabusModule } from '@/api/batchManagement';
import { useToast } from '@/shared/hooks/use-toast';

interface ModuleAccordionProps {
    module: SyllabusModule;
    batchId: number;
    onDelete: () => void;
}

export default function ModuleAccordion({ module, batchId, onDelete }: ModuleAccordionProps) {
    const { toast } = useToast();
    const queryClient = useQueryClient();

    const toggleCompletionMutation = useMutation({
        mutationFn: syllabusApi.toggleTopicCompletion,
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: ['syllabus', batchId] });
            toast({
                title: 'Topic updated',
                description: 'Topic completion status has been updated.',
            });
        },
        onError: () => {
            toast({
                title: 'Error',
                description: 'Failed to update topic. Please try again.',
                variant: 'destructive',
            });
        },
    });

    const deleteTopicMutation = useMutation({
        mutationFn: syllabusApi.deleteTopic,
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: ['syllabus', batchId] });
            toast({
                title: 'Topic deleted',
                description: 'The topic has been successfully deleted.',
            });
        },
        onError: () => {
            toast({
                title: 'Error',
                description: 'Failed to delete topic. Please try again.',
                variant: 'destructive',
            });
        },
    });

    const handleToggleTopic = (topicId: number) => {
        toggleCompletionMutation.mutate(topicId);
    };

    const progressPercentage = module.topicsCount > 0
        ? Math.round((module.completedTopicsCount / module.topicsCount) * 100)
        : 0;

    return (
        <Accordion type="single" collapsible className="w-full">
            <AccordionItem value={`module-${module.id}`} className="border rounded-lg px-4">
                <AccordionTrigger className="hover:no-underline">
                    <div className="flex items-center justify-between w-full pr-4">
                        <div className="flex items-center gap-3">
                            <Badge variant="outline" className="font-mono">
                                {module.displayOrder}
                            </Badge>
                            <div className="text-left">
                                <h3 className="font-semibold">{module.name}</h3>
                                {module.description && (
                                    <p className="text-sm text-muted-foreground">{module.description}</p>
                                )}
                            </div>
                        </div>
                        <div className="flex items-center gap-4">
                            <div className="text-sm text-muted-foreground">
                                {module.completedTopicsCount} / {module.topicsCount} completed ({progressPercentage}%)
                            </div>
                            <div className="flex gap-1">
                                <Button variant="ghost" size="icon" onClick={(e) => { e.stopPropagation(); }}>
                                    <Pencil className="h-4 w-4" />
                                </Button>
                                <Button
                                    variant="ghost"
                                    size="icon"
                                    onClick={(e) => {
                                        e.stopPropagation();
                                        onDelete();
                                    }}
                                >
                                    <Trash2 className="h-4 w-4 text-destructive" />
                                </Button>
                            </div>
                        </div>
                    </div>
                </AccordionTrigger>
                <AccordionContent>
                    <div className="space-y-2 pt-4">
                        {module.topics.length === 0 ? (
                            <div className="text-center py-8 text-muted-foreground">
                                <p>No topics yet</p>
                                <Button variant="outline" size="sm" className="mt-2">
                                    <Plus className="h-4 w-4 mr-2" />
                                    Add Topic
                                </Button>
                            </div>
                        ) : (
                            <>
                                {module.topics.map((topic) => (
                                    <div
                                        key={topic.id}
                                        className="flex items-center justify-between p-3 rounded-lg hover:bg-accent group"
                                    >
                                        <div className="flex items-center gap-3 flex-1">
                                            <Checkbox
                                                checked={topic.isCompleted}
                                                onCheckedChange={() => handleToggleTopic(topic.id)}
                                                disabled={toggleCompletionMutation.isPending}
                                            />
                                            <Badge variant="secondary" className="font-mono min-w-[2rem] justify-center">
                                                {topic.displayOrder}
                                            </Badge>
                                            <div className="flex-1">
                                                <p className={topic.isCompleted ? 'line-through text-muted-foreground' : ''}>
                                                    {topic.name}
                                                </p>
                                                {topic.description && (
                                                    <p className="text-sm text-muted-foreground">{topic.description}</p>
                                                )}
                                                {topic.isCompleted && topic.completedAt && (
                                                    <p className="text-xs text-muted-foreground">
                                                        Completed on {new Date(topic.completedAt).toLocaleDateString()}
                                                    </p>
                                                )}
                                            </div>
                                        </div>
                                        <div className="flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                                            <Button variant="ghost" size="icon">
                                                <Pencil className="h-4 w-4" />
                                            </Button>
                                            <Button
                                                variant="ghost"
                                                size="icon"
                                                onClick={() => deleteTopicMutation.mutate(topic.id)}
                                                disabled={deleteTopicMutation.isPending}
                                            >
                                                <Trash2 className="h-4 w-4 text-destructive" />
                                            </Button>
                                        </div>
                                    </div>
                                ))}
                                <Button variant="outline" size="sm" className="w-full mt-2">
                                    <Plus className="h-4 w-4 mr-2" />
                                    Add Topic
                                </Button>
                            </>
                        )}
                    </div>
                </AccordionContent>
            </AccordionItem>
        </Accordion>
    );
}
