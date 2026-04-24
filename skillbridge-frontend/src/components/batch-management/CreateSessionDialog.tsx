import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/shared/components/ui/dialog';
import { Button } from '@/shared/components/ui/button';
import { Input } from '@/shared/components/ui/input';
import { Label } from '@/shared/components/ui/label';
import { Textarea } from '@/shared/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/shared/components/ui/select';
import { Plus, Loader2 } from 'lucide-react';
import { timelineApi, syllabusApi } from '@/api/batchManagement';
import { useToast } from '@/shared/hooks/use-toast';

interface CreateSessionDialogProps {
    batchId: number;
    open: boolean;
    onOpenChange: (open: boolean) => void;
}

export default function CreateSessionDialog({ batchId, open, onOpenChange }: CreateSessionDialogProps) {
    const { toast } = useToast();
    const queryClient = useQueryClient();
    const [sessionNumber, setSessionNumber] = useState('1');
    const [title, setTitle] = useState('');
    const [description, setDescription] = useState('');
    const [topicId, setTopicId] = useState<string>('');
    const [plannedDate, setPlannedDate] = useState('');

    // Fetch syllabus to get topics
    const { data: modules = [] } = useQuery({
        queryKey: ['syllabus', batchId],
        queryFn: async () => {
            const response = await syllabusApi.getSyllabus(batchId);
            return response.data;
        },
        enabled: open,
    });

    const topics = modules.flatMap((module: any) =>
        module.topics.map((topic: any) => ({
            id: topic.id,
            name: `${module.name} - ${topic.name}`,
        }))
    );

    const createMutation = useMutation({
        mutationFn: (data: any) => timelineApi.createSession(batchId, data),
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: ['timeline', batchId] });
            toast({
                title: 'Session created',
                description: 'The session has been successfully created.',
            });
            handleClose();
        },
        onError: (error: any) => {
            toast({
                title: 'Error',
                description: error.response?.data?.message || 'Failed to create session. Please try again.',
                variant: 'destructive',
            });
        },
    });

    const handleClose = () => {
        setSessionNumber('1');
        setTitle('');
        setDescription('');
        setTopicId('');
        setPlannedDate('');
        onOpenChange(false);
    };

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();

        if (!title.trim()) {
            toast({
                title: 'Validation error',
                description: 'Session title is required.',
                variant: 'destructive',
            });
            return;
        }

        createMutation.mutate({
            sessionNumber: parseInt(sessionNumber) || 1,
            title: title.trim(),
            description: description.trim() || undefined,
            topicId: topicId ? parseInt(topicId) : undefined,
            plannedDate: plannedDate || undefined,
        });
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <form onSubmit={handleSubmit}>
                    <DialogHeader>
                        <DialogTitle>Create New Session</DialogTitle>
                        <DialogDescription>
                            Add a new session to the batch timeline.
                        </DialogDescription>
                    </DialogHeader>

                    <div className="space-y-4 py-4">
                        <div className="grid grid-cols-2 gap-4">
                            <div className="space-y-2">
                                <Label htmlFor="sessionNumber">Session Number *</Label>
                                <Input
                                    id="sessionNumber"
                                    type="number"
                                    min="1"
                                    value={sessionNumber}
                                    onChange={(e) => setSessionNumber(e.target.value)}
                                    disabled={createMutation.isPending}
                                />
                            </div>

                            <div className="space-y-2">
                                <Label htmlFor="plannedDate">Planned Date</Label>
                                <Input
                                    id="plannedDate"
                                    type="date"
                                    value={plannedDate}
                                    onChange={(e) => setPlannedDate(e.target.value)}
                                    disabled={createMutation.isPending}
                                />
                            </div>
                        </div>

                        <div className="space-y-2">
                            <Label htmlFor="title">Session Title *</Label>
                            <Input
                                id="title"
                                placeholder="e.g., Introduction to Hooks"
                                value={title}
                                onChange={(e) => setTitle(e.target.value)}
                                disabled={createMutation.isPending}
                            />
                        </div>

                        <div className="space-y-2">
                            <Label htmlFor="description">Description</Label>
                            <Textarea
                                id="description"
                                placeholder="Brief description of the session"
                                value={description}
                                onChange={(e) => setDescription(e.target.value)}
                                disabled={createMutation.isPending}
                                rows={3}
                            />
                        </div>

                        <div className="space-y-2">
                            <Label htmlFor="topic">Link to Topic (Optional)</Label>
                            <Select value={topicId} onValueChange={setTopicId} disabled={createMutation.isPending}>
                                <SelectTrigger id="topic">
                                    <SelectValue placeholder="Select a topic" />
                                </SelectTrigger>
                                <SelectContent>
                                    <SelectItem value="none">No topic</SelectItem>
                                    {topics.map((topic: any) => (
                                        <SelectItem key={topic.id} value={topic.id.toString()}>
                                            {topic.name}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>
                    </div>

                    <DialogFooter>
                        <Button
                            type="button"
                            variant="outline"
                            onClick={handleClose}
                            disabled={createMutation.isPending}
                        >
                            Cancel
                        </Button>
                        <Button type="submit" disabled={createMutation.isPending}>
                            {createMutation.isPending && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
                            <Plus className="h-4 w-4 mr-2" />
                            Create Session
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}
