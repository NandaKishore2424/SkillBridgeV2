import { useState, useEffect } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/shared/components/ui/dialog';
import { Button } from '@/shared/components/ui/button';
import { Input } from '@/shared/components/ui/input';
import { Label } from '@/shared/components/ui/label';
import { Textarea } from '@/shared/components/ui/textarea';
import { Pencil, Loader2 } from 'lucide-react';
import { syllabusApi, type SyllabusSubmodule } from '@/api/batchManagement';
import { useToast } from '@/shared/hooks/use-toast';

interface EditSubmoduleDialogProps {
    submodule: SyllabusSubmodule;
    batchId: number;
    isOpen: boolean;
    onClose: () => void;
}

export default function EditSubmoduleDialog({ submodule, batchId, isOpen, onClose }: EditSubmoduleDialogProps) {
    const { toast } = useToast();
    const queryClient = useQueryClient();
    const [name, setName] = useState('');
    const [description, setDescription] = useState('');
    const [startDate, setStartDate] = useState('');
    const [endDate, setEndDate] = useState('');
    const [weekNumber, setWeekNumber] = useState('');

    // Load submodule data when dialog opens
    useEffect(() => {
        if (isOpen && submodule) {
            setName(submodule.name);
            setDescription(submodule.description || '');
            setStartDate(submodule.startDate ? submodule.startDate.split('T')[0] : '');
            setEndDate(submodule.endDate ? submodule.endDate.split('T')[0] : '');
            setWeekNumber(submodule.weekNumber?.toString() || '');
        }
    }, [isOpen, submodule]);

    const updateMutation = useMutation({
        mutationFn: (data: {
            name: string;
            description?: string;
            startDate?: string;
            endDate?: string;
            weekNumber?: number;
        }) => syllabusApi.updateSubmodule(submodule.id, data),
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: ['syllabus', batchId] });
            toast({
                title: 'Sub-module updated',
                description: 'The sub-module has been successfully updated.',
            });
            handleClose();
        },
        onError: (error: any) => {
            toast({
                title: 'Error',
                description: error.response?.data?.message || 'Failed to update sub-module. Please try again.',
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

        updateMutation.mutate({
            name: name.trim(),
            description: description.trim() || undefined,
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
                        <DialogTitle>Edit Sub-module</DialogTitle>
                        <DialogDescription>
                            Update the sub-module details and scheduling information.
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
                                disabled={updateMutation.isPending}
                            />
                        </div>

                        <div className="space-y-2">
                            <Label htmlFor="description">Description</Label>
                            <Textarea
                                id="description"
                                placeholder="Brief description of the sub-module"
                                value={description}
                                onChange={(e) => setDescription(e.target.value)}
                                disabled={updateMutation.isPending}
                                rows={3}
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
                                disabled={updateMutation.isPending}
                            />
                        </div>

                        <div className="grid grid-cols-2 gap-4">
                            <div className="space-y-2">
                                <Label htmlFor="startDate">Start Date</Label>
                                <Input
                                    id="startDate"
                                    type="date"
                                    value={startDate}
                                    onChange={(e) => setStartDate(e.target.value)}
                                    disabled={updateMutation.isPending}
                                />
                            </div>

                            <div className="space-y-2">
                                <Label htmlFor="endDate">End Date</Label>
                                <Input
                                    id="endDate"
                                    type="date"
                                    value={endDate}
                                    onChange={(e) => setEndDate(e.target.value)}
                                    disabled={updateMutation.isPending}
                                />
                            </div>
                        </div>
                    </div>

                    <DialogFooter>
                        <Button
                            type="button"
                            variant="outline"
                            onClick={handleClose}
                            disabled={updateMutation.isPending}
                        >
                            Cancel
                        </Button>
                        <Button type="submit" disabled={updateMutation.isPending}>
                            {updateMutation.isPending && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
                            <Pencil className="h-4 w-4 mr-2" />
                            Update Sub-module
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}
