import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/shared/components/ui/dialog';
import { Button } from '@/shared/components/ui/button';
import { Input } from '@/shared/components/ui/input';
import { Label } from '@/shared/components/ui/label';
import { Textarea } from '@/shared/components/ui/textarea';
import { Pencil, Loader2 } from 'lucide-react';
import { syllabusApi, type SyllabusModule } from '@/api/batchManagement';
import { useToast } from '@/shared/hooks/use-toast';
import { apiErrorMessage } from '@/lib/apiError'
import { useSyncedState } from '@/shared/hooks/useSyncedState';

interface EditModuleDialogProps {
    module: SyllabusModule | null;
    batchId: number;
    isOpen: boolean;
    onClose: () => void;
}

export default function EditModuleDialog({ module, batchId, isOpen, onClose }: EditModuleDialogProps) {
    const { toast } = useToast();
    const queryClient = useQueryClient();
    /*
      The form is seeded from the module, and reseeded when a different module
      is opened -- or the same one opened again. Keyed on both, because the
      dialog is kept mounted: keyed on the id alone, closing it and reopening it
      on the same module would show the half-finished edit from last time.

      During render rather than in an effect, so the fields never show the
      previous module's values for a frame.
    */
    const seed = `${isOpen}:${module?.id ?? ''}`;
    const [name, setName] = useSyncedState(module?.name ?? '', seed);
    const [description, setDescription] = useSyncedState(module?.description ?? '', seed);
    const [displayOrder, setDisplayOrder] = useSyncedState(
        module?.displayOrder?.toString() ?? '1',
        seed,
    );
    const [startDate, setStartDate] = useSyncedState(module?.startDate ?? '', seed);
    const [endDate, setEndDate] = useSyncedState(module?.endDate ?? '', seed);

    const updateMutation = useMutation({
        mutationFn: (data: { name: string; description?: string; displayOrder: number; startDate?: string; endDate?: string }) =>
            syllabusApi.updateModule(module!.id, data),
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: ['syllabus', batchId] });
            toast({
                title: 'Module updated',
                description: 'The module has been successfully updated.',
            });
            onClose();
        },
        onError: (error: unknown) => {
            toast({
                title: 'Error',
                description: apiErrorMessage(error, 'Failed to update module. Please try again.'),
                variant: 'destructive',
            });
        },
    });



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

        updateMutation.mutate({
            name: name.trim(),
            description: description.trim() || undefined,
            displayOrder: parseInt(displayOrder) || 1,
            startDate: startDate || undefined,
            endDate: endDate || undefined,
        });
    };

    if (!module) return null;

    return (
        <Dialog open={isOpen} onOpenChange={onClose}>
            <DialogContent>
                <form onSubmit={handleSubmit}>
                    <DialogHeader>
                        <DialogTitle>Edit Module</DialogTitle>
                        <DialogDescription>
                            Update the module details. Changes will be reflected in the syllabus.
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
                                disabled={updateMutation.isPending}
                            />
                        </div>

                        <div className="space-y-2">
                            <Label htmlFor="description">Description</Label>
                            <Textarea
                                id="description"
                                placeholder="Brief description of the module"
                                value={description}
                                onChange={(e) => setDescription(e.target.value)}
                                disabled={updateMutation.isPending}
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
                            onClick={onClose}
                            disabled={updateMutation.isPending}
                        >
                            Cancel
                        </Button>
                        <Button type="submit" disabled={updateMutation.isPending}>
                            {updateMutation.isPending && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
                            <Pencil className="h-4 w-4 mr-2" />
                            Update Module
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}
