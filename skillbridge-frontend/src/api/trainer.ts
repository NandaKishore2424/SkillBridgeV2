/**
 * Trainer API endpoints
 * 
 * For trainer role operations
 */

import apiClient from './client'
import type { Batch, BatchStatus } from '@/shared/types'

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

export const getTrainerBatches = async (): Promise<TrainerBatch[]> => {
  const response = await apiClient.get<TrainerBatch[]>('/trainer/batches')
  return response.data
}

export const getBatchStudents = async (batchId: number): Promise<TrainerStudent[]> => {
  const response = await apiClient.get<TrainerStudent[]>(`/trainer/batches/${batchId}/students`)
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

export const updateStudentProgress = async (
  batchId: number,
  studentId: number,
  data: UpdateProgressRequest
): Promise<void> => {
  await apiClient.patch(
    `/trainer/batches/${batchId}/students/${studentId}/progress`,
    data
  )
}

