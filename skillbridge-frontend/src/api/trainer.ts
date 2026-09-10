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

export type ProgressStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'NEEDS_IMPROVEMENT'

/**
 * The body of PUT /trainer/topics/{topicId}/progress.
 *
 * This interface previously declared `topicId` and `feedback`, neither of which
 * the backend reads: the topic is in the path, and the field is `comment`. It
 * also omitted `studentId`, without which the request cannot say who is being
 * graded. Nothing noticed because nothing called it — the drift check compares
 * routes, not request shapes.
 */
export interface GradeTopicRequest {
  studentId: number
  status: ProgressStatus
  /** 0-100. Omit for "assessed, no numeric score". */
  score?: number
  comment?: string
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
  data: GradeTopicRequest
): Promise<void> => {
  await apiClient.put(`/trainer/topics/${topicId}/progress`, data)
}

/** One student's row in the grading grid for a topic. */
export interface GradingGridRow {
  progressId: number
  studentId: number
  studentName: string
  rollNumber: string
  status: ProgressStatus
  score: number | null
  comment: string | null
  gradedByTrainerName: string | null
  completedAt: string | null
  updatedAt: string | null
}

/** Every enrolled student's row for one topic, paged. */
export const getGradingGrid = async (
  topicId: number,
  page = 0,
  size = 50
): Promise<PagedResponse<GradingGridRow>> => {
  const response = await apiClient.get<PagedResponse<GradingGridRow>>(
    `/trainer/topics/${topicId}/progress`,
    { params: { page, size } }
  )
  return response.data
}

export interface BulkGradeRequest {
  studentIds: number[]
  status: ProgressStatus
  score?: number
  comment?: string
}

export interface BulkGradeResult {
  topicId: number
  topicName: string
  requested: number
  graded: number
  skippedStudentIds: number[]
  message: string
}

/**
 * Grade several students on one topic in a single request.
 *
 * One request rather than one per student is the point: the server does it in
 * one transaction, so a class either grades or does not, and a trainer marking
 * thirty students does not fire thirty round trips at a database in another
 * region.
 */
export const bulkGrade = async (
  topicId: number,
  data: BulkGradeRequest
): Promise<BulkGradeResult> => {
  const response = await apiClient.put<BulkGradeResult>(
    `/trainer/topics/${topicId}/progress/bulk`,
    data
  )
  return response.data
}

