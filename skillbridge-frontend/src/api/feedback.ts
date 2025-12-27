import api from './client';

export interface FeedbackRequest {
  studentId?: number;
  trainerId?: number;
  batchId: number;
  type: 'STUDENT_TO_TRAINER' | 'TRAINER_TO_STUDENT';
  rating: number;
  category: string;
  comments: string;
}

export interface FeedbackResponse {
  id: number;
  studentId: number;
  studentName: string;
  trainerId: number;
  trainerName: string;
  batchId: number;
  batchName: string;
  type: 'STUDENT_TO_TRAINER' | 'TRAINER_TO_STUDENT';
  rating: number;
  category: string;
  comments: string;
  createdAt: string;
}

export const feedbackApi = {
  createFeedback: async (data: FeedbackRequest): Promise<FeedbackResponse> => {
    const response = await api.post('/feedback', data);
    return response.data;
  },

  getMyFeedback: async (): Promise<FeedbackResponse[]> => {
    const response = await api.get('/feedback/my-feedback');
    return response.data;
  },

  getFeedbackByBatch: async (batchId: number): Promise<FeedbackResponse[]> => {
    const response = await api.get(`/feedback/batch/${batchId}`);
    return response.data;
  },

  getFeedbackByStudent: async (studentId: number): Promise<FeedbackResponse[]> => {
    const response = await api.get(`/feedback/student/${studentId}`);
    return response.data;
  }
};
