import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/shared/components/ui/dialog';
import { Button } from '@/shared/components/ui/button';
import { Input } from '@/shared/components/ui/input';
import { Label } from '@/shared/components/ui/label';
import { Textarea } from '@/shared/components/ui/textarea';
import { Plus, Loader2 } from 'lucide-react';
import { syllabusApi } from '@/api/batchManagement';
import { useToast } from '@/shared/hooks/use-toast';

interface AddTopicDialogProps {
    submoduleId: number;
    batchId: number;
    isOpen: boolean;
    onClose: () => void;
}

export default function AddTopicDialog({ submoduleId, batchId, isOpen, onClose }: AddTopicDialogProps) {
    const { toast } = useToast();
    const queryClient = useQueryClient();
    const [name, setName] = useState('');
    const [description, setDescription] = useState('');

    const createMutation = useMutation({
        mutationFn: (data: {
            name: string;
            description?: string;
            displayOrder: number;
        }) => syllabusApi.addTopic(submoduleId, data),
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: ['syllabus', batchId] });
            toast({
                title: 'Topic added',
                description: 'The topic has been successfully added.',
            });
            handleClose();
        },
        onError: (error: any) => {
            toast({
                title: 'Error',
                description: error.response?.data?.message || 'Failed to add topic. Please try again.',
                variant: 'destructive',
            });
        },
    });

    const handleClose = () => {
        setName('');
        setDescription('');
        onClose();
    };

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();

        if (!name.trim()) {
            toast({
                title: 'Validation error',
                description: 'Topic name is required.',
                variant: 'destructive',
            });
            return;
        }

        createMutation.mutate({
            name: name.trim(),
            description: description.trim() || undefined,
            displayOrder: 1, // Default to 1 as topics are auto-ordered
        });
    };

    return (
        <Dialog open={isOpen} onOpenChange={onClose}>
            <DialogContent>
                <form onSubmit={handleSubmit}>
                    <DialogHeader>
                        <DialogTitle>Add Topic</DialogTitle>
                        <DialogDescription>
                            Add a new topic to this sub-module.
                        </DialogDescription>
                    </DialogHeader>

                    <div className="space-y-4 py-4">
                        <div className="space-y-2">
                            <Label htmlFor="topic-name">Topic Name *</Label>
                            <Input
                                id="topic-name"
                                placeholder="e.g., Introduction to Variables"
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                disabled={createMutation.isPending}
                            />
                        </div>

                        <div className="space-y-2">
                            <Label htmlFor="topic-description">Description</Label>
                            <Textarea
                                id="topic-description"
                                placeholder="Brief description of the topic (optional)"
                                value={description}
                                onChange={(e) => setDescription(e.target.value)}
                                disabled={createMutation.isPending}
                                rows={3}
                            />
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
                            Add Topic
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}
