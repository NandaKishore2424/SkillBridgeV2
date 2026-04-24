import { useState, useEffect } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/shared/components/ui/dialog';
import { Button } from '@/shared/components/ui/button';
import { Input } from '@/shared/components/ui/input';
import { Label } from '@/shared/components/ui/label';
import { Textarea } from '@/shared/components/ui/textarea';
import { Plus, Loader2 } from 'lucide-react';
import { syllabusApi } from '@/api/batchManagement';
import { useToast } from '@/shared/hooks/use-toast';

interface CreateSubmoduleDialogProps {
    moduleId: number;
    batchId: number;
    submodulesCount: number;
    isOpen: boolean;
    onClose: () => void;
}

export default function CreateSubmoduleDialog({ moduleId, batchId, submodulesCount, isOpen, onClose }: CreateSubmoduleDialogProps) {
    const { toast } = useToast();
    const queryClient = useQueryClient();
    const [name, setName] = useState('');
    const [description, setDescription] = useState('');
    const [displayOrder, setDisplayOrder] = useState('1');
    const [startDate, setStartDate] = useState('');
    const [endDate, setEndDate] = useState('');
    const [weekNumber, setWeekNumber] = useState('');

    // Auto-calculate next display order when dialog opens
    useEffect(() => {
        if (isOpen) {
            setDisplayOrder((submodulesCount + 1).toString());
        }
    }, [isOpen, submodulesCount]);

    const createMutation = useMutation({
        mutationFn: (data: {
            name: string;
            description?: string;
            displayOrder: number;
            startDate?: string;
            endDate?: string;
            weekNumber?: number;
        }) => syllabusApi.createSubmodule(moduleId, data),
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: ['syllabus', batchId] });
            toast({
                title: 'Sub-module created',
                description: 'The sub-module has been successfully created.',
            });
            handleClose();
        },
        onError: (error: any) => {
            toast({
                title: 'Error',
                description: error.response?.data?.message || 'Failed to create sub-module. Please try again.',
                variant: 'destructive',
            });
        },
    });

    const handleClose = () => {
        setName('');
        setDescription('');
        setStartDate('');
        setEndDate('');
        setWeekNumber('');
        onClose();
    };

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();

        if (!name.trim()) {
            toast({
                title: 'Validation error',
                description: 'Sub-module name is required.',
                variant: 'destructive',
            });
            return;
        }

        createMutation.mutate({
            name: name.trim(),
            description: description.trim() || undefined,
            displayOrder: parseInt(displayOrder) || 1,
            startDate: startDate || undefined,
            endDate: endDate || undefined,
            weekNumber: weekNumber ? parseInt(weekNumber) : undefined,
        });
    };

    return (
        <Dialog open={isOpen} onOpenChange={onClose}>
            <DialogContent className="max-w-2xl">
                <form onSubmit={handleSubmit}>
                    <DialogHeader>
                        <DialogTitle>Create Sub-module</DialogTitle>
                        <DialogDescription>
                            Add a new sub-module to organize topics within this module.
                        </DialogDescription>
                    </DialogHeader>

                    <div className="space-y-4 py-4">
                        <div className="space-y-2">
                            <Label htmlFor="name">Sub-module Name *</Label>
                            <Input
                                id="name"
                                placeholder="e.g., Variables and Data Types"
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                disabled={createMutation.isPending}
                            />
                        </div>

                        <div className="space-y-2">
                            <Label htmlFor="description">Description</Label>
                            <Textarea
                                id="description"
                                placeholder="Brief description of the sub-module"
                                value={description}
                                onChange={(e) => setDescription(e.target.value)}
                                disabled={createMutation.isPending}
                                rows={3}
                            />
                        </div>

                        <div className="grid grid-cols-3 gap-4">
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

                            <div className="space-y-2">
                                <Label htmlFor="weekNumber">Week Number</Label>
                                <Input
                                    id="weekNumber"
                                    type="number"
                                    min="1"
                                    placeholder="Optional"
                                    value={weekNumber}
                                    onChange={(e) => setWeekNumber(e.target.value)}
                                    disabled={createMutation.isPending}
                                />
                            </div>
                        </div>

                        <div className="grid grid-cols-2 gap-4">
                            <div className="space-y-2">
                                <Label htmlFor="startDate">Start Date</Label>
                                <Input
                                    id="startDate"
                                    type="date"
                                    value={startDate}
                                    onChange={(e) => setStartDate(e.target.value)}
                                    disabled={createMutation.isPending}
                                />
                            </div>

                            <div className="space-y-2">
                                <Label htmlFor="endDate">End Date</Label>
                                <Input
                                    id="endDate"
                                    type="date"
                                    value={endDate}
                                    onChange={(e) => setEndDate(e.target.value)}
                                    disabled={createMutation.isPending}
                                />
                            </div>
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
                            Create Sub-module
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}
