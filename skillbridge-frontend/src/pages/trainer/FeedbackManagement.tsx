import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import * as z from 'zod';
import { getTrainerBatches, getBatchStudents } from '@/api/trainer';
import { feedbackApi } from '@/api/feedback';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/components/ui/card';
import { Button } from '@/shared/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/shared/components/ui/select';
import { Textarea } from '@/shared/components/ui/textarea';
import { Input } from '@/shared/components/ui/input';
import { Label } from '@/shared/components/ui/label';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/components/ui/tabs';
import { useToastNotifications } from '@/shared/hooks/useToastNotifications';
import { Loader2, Star } from 'lucide-react';

const feedbackSchema = z.object({
  batchId: z.string().min(1, "Batch is required"),
  studentId: z.string().min(1, "Student is required"),
  rating: z.coerce.number().min(1).max(5),
  category: z.string().min(1, "Category is required"),
  comments: z.string().min(5, "Comments must be at least 5 characters"),
});

type FeedbackFormValues = z.infer<typeof feedbackSchema>;

const FeedbackManagement = () => {
  const queryClient = useQueryClient();
  const [selectedBatchId, setSelectedBatchId] = useState<string | null>(null);
  const { showSuccess, showError } = useToastNotifications();

  const { data: batches, isLoading: isLoadingBatches } = useQuery({
    queryKey: ['trainer-batches'],
    queryFn: getTrainerBatches,
  });

  const { data: students, isLoading: isLoadingStudents } = useQuery({
    queryKey: ['batch-students', selectedBatchId],
    queryFn: () => getBatchStudents(Number(selectedBatchId)),
    enabled: !!selectedBatchId,
  });

  const { data: myFeedback, isLoading: isLoadingFeedback } = useQuery({
    queryKey: ['my-feedback'],
    queryFn: feedbackApi.getMyFeedback,
  });

  const createFeedbackMutation = useMutation({
    mutationFn: (data: FeedbackFormValues) => feedbackApi.createFeedback({
      batchId: Number(data.batchId),
      studentId: Number(data.studentId),
      type: 'TRAINER_TO_STUDENT',
      rating: data.rating,
      category: data.category,
      comments: data.comments,
    }),
    onSuccess: () => {
      showSuccess('Feedback submitted successfully');
      form.reset();
      queryClient.invalidateQueries({ queryKey: ['my-feedback'] });
    },
    onError: (error) => {
      showError('Failed to submit feedback');
      console.error(error);
    },
  });

  const form = useForm<FeedbackFormValues>({
    resolver: zodResolver(feedbackSchema),
    defaultValues: {
      rating: 5,
      category: 'General',
    },
  });

  const onSubmit = (data: FeedbackFormValues) => {
    createFeedbackMutation.mutate(data);
  };

  const categories = [
    "Technical Skills",
    "Soft Skills",
    "Punctuality",
    "Participation",
    "Project Work",
    "General"
  ];

  return (
    <div className="container mx-auto p-6 space-y-8">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">Feedback Management</h1>
          <p className="text-muted-foreground">Provide feedback to students and view history.</p>
        </div>
      </div>

      <Tabs defaultValue="give-feedback" className="space-y-4">
        <TabsList>
          <TabsTrigger value="give-feedback">Give Feedback</TabsTrigger>
          <TabsTrigger value="history">Feedback History</TabsTrigger>
        </TabsList>

        <TabsContent value="give-feedback">
          <Card>
            <CardHeader>
              <CardTitle>New Feedback</CardTitle>
              <CardDescription>Rate and comment on student performance.</CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
                <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                  <div className="space-y-2">
                    <Label>Batch</Label>
                    <Select 
                      onValueChange={(val) => {
                        setSelectedBatchId(val);
                        form.setValue('batchId', val);
                      }}
                    >
                      <SelectTrigger>
                        <SelectValue placeholder="Select Batch" />
                      </SelectTrigger>
                      <SelectContent>
                        {batches?.map((batch) => (
                          <SelectItem key={batch.id} value={String(batch.id)}>
                            {batch.name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    {form.formState.errors.batchId && (
                      <p className="text-sm text-red-500">{form.formState.errors.batchId.message}</p>
                    )}
                  </div>

                  <div className="space-y-2">
                    <Label>Student</Label>
                    <Select 
                      onValueChange={(val) => form.setValue('studentId', val)}
                      disabled={!selectedBatchId || isLoadingStudents}
                    >
                      <SelectTrigger>
                        <SelectValue placeholder={isLoadingStudents ? "Loading..." : "Select Student"} />
                      </SelectTrigger>
                      <SelectContent>
                        {students?.map((student) => (
                          <SelectItem key={student.id} value={String(student.id)}>
                            {student.fullName} ({student.rollNumber})
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    {form.formState.errors.studentId && (
                      <p className="text-sm text-red-500">{form.formState.errors.studentId.message}</p>
                    )}
                  </div>

                  <div className="space-y-2">
                    <Label>Category</Label>
                    <Select onValueChange={(val) => form.setValue('category', val)} defaultValue="General">
                      <SelectTrigger>
                        <SelectValue placeholder="Select Category" />
                      </SelectTrigger>
                      <SelectContent>
                        {categories.map((cat) => (
                          <SelectItem key={cat} value={cat}>{cat}</SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    {form.formState.errors.category && (
                      <p className="text-sm text-red-500">{form.formState.errors.category.message}</p>
                    )}
                  </div>

                  <div className="space-y-2">
                    <Label>Rating (1-5)</Label>
                    <div className="flex items-center space-x-2">
                      {[1, 2, 3, 4, 5].map((star) => (
                        <Button
                          key={star}
                          type="button"
                          variant={form.watch('rating') >= star ? "default" : "outline"}
                          size="icon"
                          className="w-10 h-10"
                          onClick={() => form.setValue('rating', star)}
                        >
                          <Star className={`w-5 h-5 ${form.watch('rating') >= star ? "fill-current" : ""}`} />
                        </Button>
                      ))}
                    </div>
                    {form.formState.errors.rating && (
                      <p className="text-sm text-red-500">{form.formState.errors.rating.message}</p>
                    )}
                  </div>
                </div>

                <div className="space-y-2">
                  <Label>Comments</Label>
                  <Textarea 
                    placeholder="Enter detailed feedback..." 
                    className="min-h-[100px]"
                    {...form.register('comments')}
                  />
                  {form.formState.errors.comments && (
                    <p className="text-sm text-red-500">{form.formState.errors.comments.message}</p>
                  )}
                </div>

                <Button type="submit" disabled={createFeedbackMutation.isPending}>
                  {createFeedbackMutation.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                  Submit Feedback
                </Button>
              </form>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="history">
          <Card>
            <CardHeader>
              <CardTitle>Feedback History</CardTitle>
              <CardDescription>View past feedback given to students.</CardDescription>
            </CardHeader>
            <CardContent>
              {isLoadingFeedback ? (
                <div className="flex justify-center p-8">
                  <Loader2 className="h-8 w-8 animate-spin" />
                </div>
              ) : (
                <div className="space-y-4">
                  {myFeedback?.filter(f => f.type === 'TRAINER_TO_STUDENT').map((feedback) => (
                    <div key={feedback.id} className="border rounded-lg p-4 space-y-2">
                      <div className="flex justify-between items-start">
                        <div>
                          <h4 className="font-semibold">{feedback.studentName}</h4>
                          <p className="text-sm text-muted-foreground">{feedback.batchName} • {feedback.category}</p>
                        </div>
                        <div className="flex items-center">
                          <Star className="w-4 h-4 fill-yellow-400 text-yellow-400 mr-1" />
                          <span className="font-bold">{feedback.rating}</span>
                        </div>
                      </div>
                      <p className="text-sm">{feedback.comments}</p>
                      <p className="text-xs text-muted-foreground text-right">
                        {new Date(feedback.createdAt).toLocaleDateString()}
                      </p>
                    </div>
                  ))}
                  {(!myFeedback || myFeedback.filter(f => f.type === 'TRAINER_TO_STUDENT').length === 0) && (
                    <p className="text-center text-muted-foreground py-8">No feedback history found.</p>
                  )}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default FeedbackManagement;
