import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/shared/components/ui/accordion';
import { Button } from '@/shared/components/ui/button';
import { Checkbox } from '@/shared/components/ui/checkbox';
import { Badge } from '@/shared/components/ui/badge';
import { Pencil, Trash2, Plus, Calendar } from 'lucide-react';
import { syllabusApi, type SyllabusModule, type SyllabusSubmodule, type SyllabusTopic } from '@/api/batchManagement';
import { useToast } from '@/shared/hooks/use-toast';
import EditModuleDialog from './EditModuleDialog';
import CreateSubmoduleDialog from './CreateSubmoduleDialog';

interface ModuleAccordionProps {
    module: SyllabusModule;
    batchId: number;
    onDelete: () => void;
}

export default function ModuleAccordion({ module, batchId, onDelete }: ModuleAccordionProps) {
    const { toast } = useToast();
    const queryClient = useQueryClient();
    const [isEditOpen, setIsEditOpen] = useState(false);
    const [isCreateSubmoduleOpen, setIsCreateSubmoduleOpen] = useState(false);

    // Format date helper
    const formatDate = (dateString?: string) => {
        if (!dateString) return '';
        try {
            const date = new Date(dateString);
            return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
        } catch {
            return '';
        }
    };

    const formatDateRange = (start?: string, end?: string) => {
        const startStr = formatDate(start);
        const endStr = formatDate(end);
        if (startStr && endStr) return `${startStr} - ${endStr}`;
        if (startStr) return startStr;
        if (endStr) return endStr;
        return '';
    };

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

    const deleteSubmoduleMutation = useMutation({
        mutationFn: syllabusApi.deleteSubmodule,
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: ['syllabus', batchId] });
            toast({
                title: 'Sub-module deleted',
                description: 'The sub-module has been successfully deleted.',
            });
        },
        onError: () => {
            toast({
                title: 'Error',
                description: 'Failed to delete sub-module. Please try again.',
                variant: 'destructive',
            });
        },
    });

    const handleToggleTopic = (topicId: number) => {
        toggleCompletionMutation.mutate(topicId);
    };

    const handleDeleteTopic = (topicId: number) => {
        if (confirm('Are you sure you want to delete this topic?')) {
            deleteTopicMutation.mutate(topicId);
        }
    };

    const handleDeleteSubmodule = (submoduleId: number) => {
        if (confirm('Are you sure you want to delete this sub-module? This will also delete all topics within it.')) {
            deleteSubmoduleMutation.mutate(submoduleId);
        }
    };

    const dateRange = formatDateRange(module.startDate, module.endDate);
    const completionPercentage = module.totalTopicsCount > 0
        ? Math.round((module.completedTopicsCount / module.totalTopicsCount) * 100)
        : 0;

    return (
        <>
            <AccordionItem value={`module-${module.id}`} className="border rounded-lg px-4 mb-3">
                <AccordionTrigger className="hover:no-underline">
                    <div className="flex items-center justify-between w-full pr-4">
                        <div className="flex items-center gap-3">
                            <span className="font-semibold text-lg">{module.name}</span>
                            {dateRange && (
                                <Badge variant="outline" className="flex items-center gap-1">
                                    <Calendar className="h-3 w-3" />
                                    {dateRange}
                                </Badge>
                            )}
                        </div>
                        <div className="flex items-center gap-3">
                            <Badge variant="secondary">
                                {module.submodulesCount} sub-module{module.submodulesCount !== 1 ? 's' : ''}
                            </Badge>
                            <Badge variant="secondary">
                                {module.completedTopicsCount}/{module.totalTopicsCount} topics
                            </Badge>
                            <Badge variant={completionPercentage === 100 ? 'default' : 'outline'}>
                                {completionPercentage}%
                            </Badge>
                        </div>
                    </div>
                </AccordionTrigger>
                <AccordionContent className="pt-4">
                    {module.description && (
                        <p className="text-sm text-muted-foreground mb-4">{module.description}</p>
                    )}

                    <div className="flex gap-2 mb-4">
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={() => setIsEditOpen(true)}
                        >
                            <Pencil className="h-4 w-4 mr-2" />
                            Edit Module
                        </Button>
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={onDelete}
                            className="text-destructive hover:text-destructive"
                        >
                            <Trash2 className="h-4 w-4 mr-2" />
                            Delete Module
                        </Button>
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={() => setIsCreateSubmoduleOpen(true)}
                        >
                            <Plus className="h-4 w-4 mr-2" />
                            Add Sub-module
                        </Button>
                    </div>

                    {/* Sub-modules */}
                    {module.submodules && module.submodules.length > 0 ? (
                        <Accordion type="multiple" className="space-y-2">
                            {module.submodules.map((submodule) => (
                                <SubmoduleAccordion
                                    key={submodule.id}
                                    submodule={submodule}
                                    onToggleTopic={handleToggleTopic}
                                    onDeleteTopic={handleDeleteTopic}
                                    onDeleteSubmodule={() => handleDeleteSubmodule(submodule.id)}
                                />
                            ))}
                        </Accordion>
                    ) : (
                        <p className="text-sm text-muted-foreground text-center py-8">
                            No sub-modules yet. Click "Add Sub-module" to get started.
                        </p>
                    )}
                </AccordionContent>
            </AccordionItem>

            <EditModuleDialog
                module={module}
                batchId={batchId}
                isOpen={isEditOpen}
                onClose={() => setIsEditOpen(false)}
            />

            <CreateSubmoduleDialog
                moduleId={module.id}
                batchId={batchId}
                submodulesCount={module.submodulesCount}
                isOpen={isCreateSubmoduleOpen}
                onClose={() => setIsCreateSubmoduleOpen(false)}
            />
        </>
    );
}

// Sub-component for rendering a sub-module
interface SubmoduleAccordionProps {
    submodule: SyllabusSubmodule;
    onToggleTopic: (topicId: number) => void;
    onDeleteTopic: (topicId: number) => void;
    onDeleteSubmodule: () => void;
}

function SubmoduleAccordion({
    submodule,
    onToggleTopic,
    onDeleteTopic,
    onDeleteSubmodule
}: SubmoduleAccordionProps) {
    const formatDateRange = (start?: string, end?: string) => {
        if (!start && !end) return '';
        try {
            const formatDate = (dateString: string) => {
                const date = new Date(dateString);
                return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
            };
            const startStr = start ? formatDate(start) : '';
            const endStr = end ? formatDate(end) : '';
            if (startStr && endStr) return `${startStr} - ${endStr}`;
            if (startStr) return startStr;
            if (endStr) return endStr;
        } catch {
            return '';
        }
        return '';
    };

    const dateRange = formatDateRange(submodule.startDate, submodule.endDate);
    const weekLabel = submodule.weekNumber ? `Week ${submodule.weekNumber}` : '';
    const schedulingInfo = weekLabel || dateRange;

    const completionPercentage = submodule.topicsCount > 0
        ? Math.round((submodule.completedTopicsCount / submodule.topicsCount) * 100)
        : 0;

    return (
        <AccordionItem value={`submodule-${submodule.id}`} className="border rounded-md">
            <AccordionTrigger className="px-4 py-2 hover:no-underline">
                <div className="flex items-center justify-between w-full pr-4">
                    <div className="flex items-center gap-2">
                        <span className="font-medium">{submodule.name}</span>
                        {schedulingInfo && (
                            <Badge variant="outline" className="text-xs">
                                {schedulingInfo}
                            </Badge>
                        )}
                    </div>
                    <div className="flex items-center gap-2">
                        <Badge variant="secondary" className="text-xs">
                            {submodule.completedTopicsCount}/{submodule.topicsCount}
                        </Badge>
                        <Badge
                            variant={completionPercentage === 100 ? 'default' : 'outline'}
                            className="text-xs"
                        >
                            {completionPercentage}%
                        </Badge>
                    </div>
                </div>
            </AccordionTrigger>
            <AccordionContent className="px-4 pb-2">
                {submodule.description && (
                    <p className="text-sm text-muted-foreground mb-3">{submodule.description}</p>
                )}

                <div className="flex gap-2 mb-3">
                    <Button variant="ghost" size="sm" className="h-7 text-xs">
                        <Pencil className="h-3 w-3 mr-1" />
                        Edit
                    </Button>
                    <Button
                        variant="ghost"
                        size="sm"
                        className="h-7 text-xs text-destructive hover:text-destructive"
                        onClick={onDeleteSubmodule}
                    >
                        <Trash2 className="h-3 w-3 mr-1" />
                        Delete
                    </Button>
                    <Button variant="ghost" size="sm" className="h-7 text-xs">
                        <Plus className="h-3 w-3 mr-1" />
                        Add Topic
                    </Button>
                </div>

                {/* Topics */}
                {submodule.topics && submodule.topics.length > 0 ? (
                    <div className="space-y-2">
                        {submodule.topics.map((topic) => (
                            <TopicRow
                                key={topic.id}
                                topic={topic}
                                onToggle={() => onToggleTopic(topic.id)}
                                onDelete={() => onDeleteTopic(topic.id)}
                            />
                        ))}
                    </div>
                ) : (
                    <p className="text-xs text-muted-foreground text-center py-4">
                        No topics yet. Click "Add Topic" to get started.
                    </p>
                )}
            </AccordionContent>
        </AccordionItem>
    );
}

// Sub-component for rendering a topic
interface TopicRowProps {
    topic: SyllabusTopic;
    onToggle: () => void;
    onDelete: () => void;
}

function TopicRow({ topic, onToggle, onDelete }: TopicRowProps) {
    return (
        <div className="flex items-center justify-between p-2 rounded-md hover:bg-accent/50 transition-colors">
            <div className="flex items-center gap-3 flex-1">
                <Checkbox
                    checked={topic.isCompleted}
                    onCheckedChange={onToggle}
                    id={`topic-${topic.id}`}
                />
                <label
                    htmlFor={`topic-${topic.id}`}
                    className={`text-sm cursor-pointer flex-1 ${topic.isCompleted ? 'line-through text-muted-foreground' : ''
                        }`}
                >
                    {topic.name}
                    {topic.description && (
                        <span className="block text-xs text-muted-foreground mt-1">
                            {topic.description}
                        </span>
                    )}
                </label>
            </div>
            <div className="flex items-center gap-1">
                <Button variant="ghost" size="sm" className="h-7 w-7 p-0">
                    <Pencil className="h-3 w-3" />
                </Button>
                <Button
                    variant="ghost"
                    size="sm"
                    className="h-7 w-7 p-0 text-destructive hover:text-destructive"
                    onClick={onDelete}
                >
                    <Trash2 className="h-3 w-3" />
                </Button>
            </div>
        </div>
    );
}
