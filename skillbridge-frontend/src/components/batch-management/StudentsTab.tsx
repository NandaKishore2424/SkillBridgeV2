import { useQuery } from '@tanstack/react-query';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/components/ui/card';
import { Button } from '@/shared/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/shared/components/ui/table';
import { UserPlus, Loader2 } from 'lucide-react';
import { enrollmentApi, type BatchEnrollment } from '@/api/batchManagement';
import { useAuth } from '@/shared/contexts/AuthContext';

interface StudentsTabProps {
    batchId: number;
}

export default function StudentsTab({ batchId }: StudentsTabProps) {
    const { user } = useAuth();
    const isAdmin = user?.role === 'ADMIN';

    const { data: enrollment, isLoading } = useQuery({
        queryKey: ['enrollment', batchId],
        queryFn: async () => {
            const response = await enrollmentApi.getBatchEnrollments(batchId);
            return response.data as BatchEnrollment;
        },
        enabled: isAdmin, // Only admins can view enrollments directly
    });

    if (!isAdmin) {
        return (
            <Card>
                <CardContent className="flex flex-col items-center justify-center py-12 text-center">
                    <UserPlus className="h-12 w-12 text-muted-foreground mb-4" />
                    <h3 className="text-lg font-semibold mb-2">Student Management</h3>
                    <p className="text-muted-foreground mb-4 max-w-sm">
                        As a trainer, you can request to add or remove students from this batch.
                        Requests will need administrator approval.
                    </p>
                    <Button>
                        <UserPlus className="h-4 w-4 mr-2" />
                        Request Student Addition
                    </Button>
                </CardContent>
            </Card>
        );
    }

    if (isLoading) {
        return (
            <Card>
                <CardContent className="flex items-center justify-center py-12">
                    <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
                </CardContent>
            </Card>
        );
    }

    const students = enrollment?.enrolledStudents || [];

    return (
        <div className="space-y-4">
            {/* Header */}
            <Card>
                <CardHeader>
                    <div className="flex items-center justify-between">
                        <div>
                            <CardTitle>Enrolled Students ({students.length})</CardTitle>
                            <CardDescription>
                                Manage student enrollments for this batch
                            </CardDescription>
                        </div>
                        <Button>
                            <UserPlus className="h-4 w-4 mr-2" />
                            Add Student
                        </Button>
                    </div>
                </CardHeader>
            </Card>

            {/* Students Table */}
            {students.length === 0 ? (
                <Card>
                    <CardContent className="flex flex-col items-center justify-center py-12 text-center">
                        <UserPlus className="h-12 w-12 text-muted-foreground mb-4" />
                        <h3 className="text-lg font-semibold mb-2">No students enrolled</h3>
                        <p className="text-muted-foreground mb-4 max-w-sm">
                            Add stu students to this batch to get started.
                        </p>
                        <Button>
                            <UserPlus className="h-4 w-4 mr-2" />
                            Add First Student
                        </Button>
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
                                    <TableHead>Department</TableHead>
                                    <TableHead>Year</TableHead>
                                    <TableHead className="text-right">Actions</TableHead>
                                </TableRow>
                            </TableHeader>
                            <TableBody>
                                {students.map((student) => (
                                    <TableRow key={student.studentId}>
                                        <TableCell className="font-mono">{student.rollNumber}</TableCell>
                                        <TableCell className="font-medium">{student.fullName}</TableCell>
                                        <TableCell>{student.email}</TableCell>
                                        <TableCell>{student.department}</TableCell>
                                        <TableCell>{student.year}</TableCell>
                                        <TableCell className="text-right">
                                            <Button variant="ghost" size="sm" className="text-destructive">
                                                Remove
                                            </Button>
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
