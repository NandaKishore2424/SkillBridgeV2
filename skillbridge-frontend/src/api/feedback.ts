import api from './client';
import type { PagedResponse, PageParams } from './paging';

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

/**
 * The three feedback reads are paged.
 *
 * Feedback is append-only and nothing prunes it, so these lists grow for the
 * life of the account. The screens on top of them have no pager yet and read
 * the first page at the server's maximum size -- see `itemsOf` in ./paging.
 */
export const feedbackApi = {
  createFeedback: async (data: FeedbackRequest): Promise<FeedbackResponse> => {
    const response = await api.post('/feedback', data);
    return response.data;
  },

  getMyFeedback: async (params: PageParams = {}): Promise<PagedResponse<FeedbackResponse>> => {
    const response = await api.get('/feedback/my-feedback', { params });
    return response.data;
  },

  getFeedbackByBatch: async (
    batchId: number,
    params: PageParams = {},
  ): Promise<PagedResponse<FeedbackResponse>> => {
    const response = await api.get(`/feedback/batch/${batchId}`, { params });
    return response.data;
  },

  getFeedbackByStudent: async (
    studentId: number,
    params: PageParams = {},
  ): Promise<PagedResponse<FeedbackResponse>> => {
    const response = await api.get(`/feedback/student/${studentId}`, { params });
    return response.data;
  }
};
