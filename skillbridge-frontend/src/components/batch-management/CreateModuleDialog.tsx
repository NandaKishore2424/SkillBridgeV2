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

interface CreateModuleDialogProps {
    batchId: number;
    open: boolean;
    onOpenChange: (open: boolean) => void;
}

export default function CreateModuleDialog({ batchId, open, onOpenChange }: CreateModuleDialogProps) {
    const { toast } = useToast();
    const queryClient = useQueryClient();
    const [name, setName] = useState('');
    const [description, setDescription] = useState('');
    const [displayOrder, setDisplayOrder] = useState('1');

    const createMutation = useMutation({
        mutationFn: (data: { name: string; description?: string; displayOrder: number }) =>
            syllabusApi.createModule(batchId, data),
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: ['syllabus', batchId] });
            toast({
                title: 'Module created',
                description: 'The module has been successfully created.',
            });
            handleClose();
        },
        onError: (error: any) => {
            toast({
                title: 'Error',
                description: error.response?.data?.message || 'Failed to create module. Please try again.',
                variant: 'destructive',
            });
        },
    });

    const handleClose = () => {
        setName('');
        setDescription('');
        setDisplayOrder('1');
        onOpenChange(false);
    };

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();

        if (!name.trim()) {
            toast({
                title: 'Validation error',
                description: 'Module name is required.',
                variant: 'destructive',
            });
            return;
        }

        createMutation.mutate({
            name: name.trim(),
            description: description.trim() || undefined,
            displayOrder: parseInt(displayOrder) || 1,
        });
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <form onSubmit={handleSubmit}>
                    <DialogHeader>
                        <DialogTitle>Create New Module</DialogTitle>
                        <DialogDescription>
                            Add a new module to the batch syllabus. You can add topics later.
                        </DialogDescription>
                    </DialogHeader>

                    <div className="space-y-4 py-4">
                        <div className="space-y-2">
                            <Label htmlFor="name">Module Name *</Label>
                            <Input
                                id="name"
                                placeholder="e.g., Introduction to React"
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                disabled={createMutation.isPending}
                            />
                        </div>

                        <div className="space-y-2">
                            <Label htmlFor="description">Description</Label>
                            <Textarea
                                id="description"
                                placeholder="Brief description of the module"
                                value={description}
                                onChange={(e) => setDescription(e.target.value)}
                                disabled={createMutation.isPending}
                                rows={3}
                            />
                        </div>

                        <div className="space-y-2">
                            <Label htmlFor="displayOrder">Display Order</Label>
                            <Input
                                id="displayOrder"
                                type="number"
                                min="1"
                                value={displayOrder}
                                onChange={(e) => setDisplayOrder(e.target.value)}
                                disabled={createMutation.isPending}
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
                            Create Module
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}
