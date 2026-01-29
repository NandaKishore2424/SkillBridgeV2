import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/shared/components/ui/dialog';
import { Button } from '@/shared/components/ui/button';
import { Label } from '@/shared/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/shared/components/ui/select';
import { Copy, Loader2 } from 'lucide-react';
import { syllabusApi, batchApi } from '@/api/batchManagement';
import { useToast } from '@/shared/hooks/use-toast';

interface CopySyllabusDialogProps {
    targetBatchId: number;
    open: boolean;
    onOpenChange: (open: boolean) => void;
}

export default function CopySyllabusDialog({ targetBatchId, open, onOpenChange }: CopySyllabusDialogProps) {
    const { toast } = useToast();
    const queryClient = useQueryClient();
    const [sourceBatchId, setSourceBatchId] = useState<string>('');

    // Fetch all batches for selection
    const { data: batches = [], isLoading } = useQuery({
        queryKey: ['batches'],
        queryFn: async () => {
            const response = await batchApi.getAllBatches();
            return response.data.filter((batch: any) => batch.id !== targetBatchId);
        },
        enabled: open,
    });

    const copyMutation = useMutation({
        mutationFn: () => syllabusApi.copySyllabus(targetBatchId, parseInt(sourceBatchId)),
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: ['syllabus', targetBatchId] });
            toast({
                title: 'Syllabus copied',
                description: 'The syllabus has been successfully copied to this batch.',
            });
            handleClose();
        },
        onError: (error: any) => {
            toast({
                title: 'Error',
                description: error.response?.data?.message || 'Failed to copy syllabus. Please try again.',
                variant: 'destructive',
            });
        },
    });

    const handleClose = () => {
        setSourceBatchId('');
        onOpenChange(false);
    };

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();

        if (!sourceBatchId) {
            toast({
                title: 'Validation error',
                description: 'Please select a batch to copy from.',
                variant: 'destructive',
            });
            return;
        }

        copyMutation.mutate();
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <form onSubmit={handleSubmit}>
                    <DialogHeader>
                        <DialogTitle>Copy Syllabus from Another Batch</DialogTitle>
                        <DialogDescription>
                            Select a batch to copy its entire syllabus structure (modules and topics) to this batch.
                        </DialogDescription>
                    </DialogHeader>

                    <div className="space-y-4 py-4">
                        <div className="space-y-2">
                            <Label htmlFor="sourceBatch">Source Batch</Label>
                            {isLoading ? (
                                <div className="flex items-center justify-center py-4">
                                    <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                                </div>
                            ) : (
                                <Select value={sourceBatchId} onValueChange={setSourceBatchId} disabled={copyMutation.isPending}>
                                    <SelectTrigger id="sourceBatch">
                                        <SelectValue placeholder="Select a batch" />
                                    </SelectTrigger>
                                    <SelectContent>
                                        {batches.length === 0 ? (
                                            <div className="text-sm text-muted-foreground text-center py-2">
                                                No other batches available
                                            </div>
                                        ) : (
                                            batches.map((batch: any) => (
                                                <SelectItem key={batch.id} value={batch.id.toString()}>
                                                    {batch.name}
                                                </SelectItem>
                                            ))
                                        )}
                                    </SelectContent>
                                </Select>
                            )}
                            <p className="text-sm text-muted-foreground">
                                All modules and topics will be copied. Topic completion status will be reset.
                            </p>
                        </div>
                    </div>

                    <DialogFooter>
                        <Button
                            type="button"
                            variant="outline"
                            onClick={handleClose}
                            disabled={copyMutation.isPending}
                        >
                            Cancel
                        </Button>
                        <Button type="submit" disabled={copyMutation.isPending || !sourceBatchId}>
                            {copyMutation.isPending && <Loader2 className="h-4 w-4 mr-2 animate-spin" />}
                            <Copy className="h-4 w-4 mr-2" />
                            Copy Syllabus
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}
