/**
 * Trainer API endpoints
 * 
 * For trainer role operations
 */

import apiClient from './client'
import type { Batch } from '@/shared/types'
import type { PagedResponse, PageParams } from './paging'

// ==================== Trainer Dashboard ====================

export interface TrainerDashboardStats {
  assignedBatches: number
  activeBatches: number
  totalStudents: number
  pendingProgressUpdates: number
}

export interface TrainerBatch extends Batch {
  enrolledCount: number
  syllabus?: {
    id: number
    title: string
    topicCount: number
  }
}

export interface TrainerStudent {
  id: number
  userId: number
  rollNumber: string
  fullName: string
  email: string
  enrolledAt: string
  progressSummary?: {
    totalTopics: number
    completedTopics: number
    inProgressTopics: number
    pendingTopics: number
  }
}

export const getTrainerDashboardStats = async (): Promise<TrainerDashboardStats> => {
  const response = await apiClient.get<TrainerDashboardStats>('/trainer/dashboard/stats')
  return response.data
}

/** The trainer's assigned batches. Paged -- they accumulate over time. */
export const getTrainerBatches = async (
  params: PageParams = {},
): Promise<PagedResponse<TrainerBatch>> => {
  const response = await apiClient.get<PagedResponse<TrainerBatch>>('/trainer/batches', { params })
  return response.data
}

/** Students on one batch. Paged -- this is the list that grows with class size. */
export const getBatchStudents = async (
  batchId: number,
  params: PageParams = {},
): Promise<PagedResponse<TrainerStudent>> => {
  const response = await apiClient.get<PagedResponse<TrainerStudent>>(
    `/trainer/batches/${batchId}/students`, { params })
  return response.data
}

// ==================== Progress Tracking ====================

export interface ProgressTopic {
  id: number
  title: string
  description?: string
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'NEEDS_IMPROVEMENT'
  feedback?: string
  updatedAt?: string
}

export interface StudentProgress {
  studentId: number
  studentName: string
  rollNumber: string
  topics: ProgressTopic[]
}

export const getStudentProgress = async (
  batchId: number,
  studentId: number
): Promise<StudentProgress> => {
  const response = await apiClient.get<StudentProgress>(
    `/trainer/batches/${batchId}/students/${studentId}/progress`
  )
  return response.data
}

export interface UpdateProgressRequest {
  topicId: number
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'NEEDS_IMPROVEMENT'
  feedback?: string
}

/**
 * Grade one topic for one student.
 *
 * Was PATCH /trainer/batches/{batchId}/students/{studentId}/progress, which the
 * backend never exposed. Phase 04 shipped grading addressed by topic instead:
 * PUT /trainer/topics/{topicId}/progress with the student in the body. The
 * batch is implied by the topic.
 */
export const updateStudentProgress = async (
  topicId: number,
  data: UpdateProgressRequest
): Promise<void> => {
  await apiClient.put(`/trainer/topics/${topicId}/progress`, data)
}

