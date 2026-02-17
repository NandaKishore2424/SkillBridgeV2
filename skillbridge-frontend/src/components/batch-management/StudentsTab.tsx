import { useQuery } from '@tanstack/react-query';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/shared/components/ui/table';
import { Users, Loader2, Mail } from 'lucide-react';
import { getBatchStudents } from '@/api/trainer';

interface StudentsTabProps {
    batchId: number;
}

export default function StudentsTab({ batchId }: StudentsTabProps) {
    const { data: students, isLoading } = useQuery({
        queryKey: ['batch-students', batchId],
        queryFn: () => getBatchStudents(batchId),
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

    const studentList = students || [];

    return (
        <div className="space-y-4">
            {/* Header */}
            <Card>
                <CardHeader>
                    <div className="flex items-center justify-between">
                        <div>
                            <CardTitle className="flex items-center gap-2">
                                <Users className="h-5 w-5" />
                                Enrolled Students ({studentList.length})
                            </CardTitle>
                            <CardDescription>
                                Students enrolled in this batch
                            </CardDescription>
                        </div>
                    </div>
                </CardHeader>
            </Card>

            {/* Students Table */}
            {studentList.length === 0 ? (
                <Card>
                    <CardContent className="flex flex-col items-center justify-center py-12 text-center">
                        <Users className="h-12 w-12 text-muted-foreground mb-4" />
                        <h3 className="text-lg font-semibold mb-2">No students enrolled</h3>
                        <p className="text-muted-foreground max-w-sm">
                            No students have been enrolled in this batch yet.
                        </p>
                    </CardContent>
                </Card>
            ) : (
                <Card>
                    <CardContent className="p-0">
                        <Table>
                            <TableHeader>
                                <TableRow>
                                    <TableHead>Roll Number</TableHead>
                                    <TableHead>Name</TableHead>
                                    <TableHead>Email</TableHead>
                                </TableRow>
                            </TableHeader>
                            <TableBody>
                                {studentList.map((student) => (
                                    <TableRow key={student.id}>
                                        <TableCell className="font-mono">{student.rollNumber}</TableCell>
                                        <TableCell className="font-medium">{student.fullName}</TableCell>
                                        <TableCell>
                                            <a href={`mailto:${student.email}`} className="flex items-center gap-1 text-primary hover:underline">
                                                <Mail className="h-3 w-3" />
                                                {student.email}
                                            </a>
                                        </TableCell>
                                    </TableRow>
                                ))}
                            </TableBody>
                        </Table>
                    </CardContent>
                </Card>
            )}
        </div>
    );
}
