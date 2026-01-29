import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/components/ui/card';
import { Button } from '@/shared/components/ui/button';
import { Badge } from '@/shared/components/ui/badge';
import { Plus, Calendar, Loader2 } from 'lucide-react';
import { timelineApi, type TimelineSession } from '@/api/batchManagement';
import CreateSessionDialog from './CreateSessionDialog';

interface TimelineTabProps {
    batchId: number;
}

export default function TimelineTab({ batchId }: TimelineTabProps) {
    const [isCreateOpen, setIsCreateOpen] = useState(false);

    const { data: sessions = [], isLoading } = useQuery({
        queryKey: ['timeline', batchId],
        queryFn: async () => {
            const response = await timelineApi.getTimeline(batchId);
            return response.data as TimelineSession[];
        },
    });

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
                            <CardTitle>Timeline & Sessions</CardTitle>
                            <CardDescription>
                                Manage session schedule and timeline for this batch
                            </CardDescription>
                        </div>
                        <Button onClick={() => setIsCreateOpen(true)}>
                            <Plus className="h-4 w-4 mr-2" />
                            Add Session
                        </Button>
                    </div>
                </CardHeader>
            </Card>

            {/* Sessions List */}
            {sessions.length === 0 ? (
                <Card>
                    <CardContent className="flex flex-col items-center justify-center py-12 text-center">
                        <Calendar className="h-12 w-12 text-muted-foreground mb-4" />
                        <h3 className="text-lg font-semibold mb-2">No sessions yet</h3>
                        <p className="text-muted-foreground mb-4 max-w-sm">
                            Start organizing your batch by creating timeline sessions.
                        </p>
                        <Button onClick={() => setIsCreateOpen(true)}>
                            <Plus className="h-4 w-4 mr-2" />
                            Create First Session
                        </Button>
                    </CardContent>
                </Card>
            ) : (
                <div className="space-y-3">
                    {sessions.map((session) => (
                        <Card key={session.id}>
                            <CardContent className="p-4">
                                <div className="flex items-start justify-between">
                                    <div className="flex items-start gap-3 flex-1">
                                        <Badge variant="outline" className="font-mono mt-1">
                                            #{session.sessionNumber}
                                        </Badge>
                                        <div className="flex-1">
                                            <h4 className="font-semibold">{session.title}</h4>
                                            {session.description && (
                                                <p className="text-sm text-muted-foreground mt-1">{session.description}</p>
                                            )}
                                            <div className="flex gap-4 mt-2">
                                                {session.plannedDate && (
                                                    <div className="flex items-center gap-1 text-sm text-muted-foreground">
                                                        <Calendar className="h-3.5 w-3.5" />
                                                        {new Date(session.plannedDate).toLocaleDateString()}
                                                    </div>
                                                )}
                                                {session.topicName && (
                                                    <Badge variant="secondary" className="text-xs">
                                                        {session.topicName}
                                                    </Badge>
                                                )}
                                            </div>
                                        </div>
                                    </div>
                                    <div className="flex gap-1">
                                        <Button variant="ghost" size="sm">
                                            Edit
                                        </Button>
                                        <Button variant="ghost" size="sm" className="text-destructive">
                                            Delete
                                        </Button>
                                    </div>
                                </div>
                            </CardContent>
                        </Card>
                    ))}
                </div>
            )}

            {/* Dialog */}
            <CreateSessionDialog
                batchId={batchId}
                open={isCreateOpen}
                onOpenChange={setIsCreateOpen}
            />
        </div>
    );
}
