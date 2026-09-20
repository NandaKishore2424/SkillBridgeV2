/**
 * Student API endpoints
 * 
 * For student role operations
 */

import apiClient from './client'
import type { Batch } from '@/shared/types'
import type { PagedResponse, PageParams } from './paging'

// ==================== Student Dashboard ====================

/**
 * What `GET /student/dashboard/stats` returns, in full.
 *
 * Four of these were missing here while the backend was sending them, so the
 * dashboard could not show them -- including `overallProgressPercent`, which is
 * the one number a student opens the page for. A type that lists a subset of a
 * response is not wrong in any way a compiler can see; the fields simply become
 * invisible.
 */
export interface StudentDashboardStats {
  enrolledBatches: number
  activeBatches: number
  completedBatches: number
  upcomingBatches: number
  /** Applications submitted and not yet reviewed by an admin. */
  pendingApplications: number
  totalTopicsCompleted: number
  totalTopicsAssigned: number
  /**
   * Weighted completion across every enrolled batch, 0-100.
   *
   * Weighted rather than completed/assigned, so partly finished work counts for
   * something. The server computes it; nothing here recomputes it, because two
   * implementations of a percentage is how a bar starts disagreeing with the
   * number printed beside it.
   */
  overallProgressPercent: number
}

export interface RecommendedBatch extends Batch {
  matchScore: number
  matchReasons: string[]
  trainerNames: string[]
  companyNames: string[]
  enrolledCount: number
  maxEnrollments?: number
}

export interface StudentBatch extends Batch {
  enrolledAt: string
  trainers: Array<{
    id: number
    fullName: string
    email: string
  }>
  companies: Array<{
    id: number
    name: string
    domain?: string
  }>
  progress?: {
    totalTopics: number
    completedTopics: number
    inProgressTopics: number
    pendingTopics: number
    completionPercentage: number
  }
}

/** The four outcomes a trainer can record. Shared with the grading screen. */
export type ProgressStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'NEEDS_IMPROVEMENT'

export interface StudentProgressTopic {
  id: number
  title: string
  description?: string
  status: ProgressStatus
  feedback?: string
  updatedAt?: string
}

export const getStudentDashboardStats = async (): Promise<StudentDashboardStats> => {
  const response = await apiClient.get<StudentDashboardStats>('/student/dashboard/stats')
  return response.data
}

/**
 * Top recommendations. Deliberately NOT paged: the backend caps this at six by
 * construction, so it is bounded already and the ranking is the point.
 */
export const getRecommendedBatches = async (): Promise<RecommendedBatch[]> => {
  const response = await apiClient.get<RecommendedBatch[]>('/student/batches/recommended')
  return response.data
}

/**
 * Batches open to this student. Paged -- every OPEN, ACTIVE or UPCOMING batch
 * in the college, so it grows with the college, not with the student.
 */
export const getAllAvailableBatches = async (
  params: PageParams = {},
): Promise<PagedResponse<Batch>> => {
  const response = await apiClient.get<PagedResponse<Batch>>('/student/batches/available', { params })
  return response.data
}

/** Batches the student is enrolled in. Paged -- enrollments accumulate. */
export const getStudentBatches = async (
  params: PageParams = {},
): Promise<PagedResponse<StudentBatch>> => {
  const response = await apiClient.get<PagedResponse<StudentBatch>>('/student/batches', { params })
  return response.data
}

export const getBatchDetails = async (batchId: number): Promise<StudentBatch> => {
  const response = await apiClient.get<StudentBatch>(`/student/batches/${batchId}`)
  return response.data
}

// ==================== Batch Enrollment ====================

export interface ApplyToBatchRequest {
  batchId: number
}

export interface EnrollmentResponse {
  id: number
  batchId: number
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  appliedAt: string
}

export const applyToBatch = async (batchId: number): Promise<void> => {
  await apiClient.post(`/student/batches/apply`, { batchId })
}

// ==================== Progress Tracking ====================

/**
 * The flat per-topic list, from the older `/progress` endpoint.
 *
 * Prefer {@link getMyProgressDetail}. This one loses the curriculum structure
 * -- a bare array of topics with no module or sub-module around them -- and it
 * carries no score and no grading trainer. The backend keeps it for the
 * contract it already published; nothing in this app calls it.
 */
export const getMyProgress = async (batchId: number): Promise<{
  batchId: number
  batchName: string
  topics: StudentProgressTopic[]
}> => {
  const response = await apiClient.get<{
    batchId: number
    batchName: string
    topics: StudentProgressTopic[]
  }>(`/student/batches/${batchId}/progress`)
  return response.data
}

// ==================== Progress Detail (curriculum tree) ====================

/**
 * The shapes below mirror `BatchProgressDTO` and the DTOs under it.
 *
 * The server returns progress already nested as the curriculum -- module,
 * sub-module, topic -- so the client renders it rather than regrouping a flat
 * list. Each level carries its own rollup, which is why `weightedPercent`
 * appears three times: a module's percentage is not the average of its
 * sub-modules' when they hold different numbers of topics.
 */
export interface TopicProgressDetail {
  progressId: number
  topicId: number
  topicName: string
  topicDescription: string | null
  displayOrder: number | null
  status: ProgressStatus
  score: number | null
  comment: string | null
  gradedByTrainerId: number | null
  gradedByTrainerName: string | null
  startedAt: string | null
  completedAt: string | null
  updatedAt: string | null
}

export interface SubmoduleProgressDetail {
  submoduleId: number
  submoduleName: string
  displayOrder: number | null
  weekNumber: number | null
  topicsTotal: number
  topicsCompleted: number
  weightedPercent: number
  topics: TopicProgressDetail[]
}

export interface ModuleProgressDetail {
  moduleId: number
  moduleName: string
  displayOrder: number | null
  startDate: string | null
  endDate: string | null
  topicsTotal: number
  topicsCompleted: number
  weightedPercent: number
  submodules: SubmoduleProgressDetail[]
}

export interface BatchProgressDetail {
  batchId: number
  batchName: string
  studentId: number
  studentName: string
  topicsTotal: number
  topicsCompleted: number
  topicsInProgress: number
  topicsNeedsWork: number
  topicsPending: number
  /** Weighted, not completed/total -- partly-done work counts for something. */
  weightedPercent: number
  averageScore: number | null
  lastActivityAt: string | null
  modules: ModuleProgressDetail[]
}

/**
 * The signed-in student's curriculum and status for one batch.
 *
 * Deliberately not paged. The response is a whole curriculum, and a page of a
 * tree is not a tree -- cutting it at twenty rows would drop modules, not just
 * topics. It is bounded by the syllabus rather than by anything that
 * accumulates, so it stays the size a trainer wrote.
 *
 * There is no student id in the path: it comes from the token, so there is no
 * id for one student to swap for another's.
 */
export const getMyProgressDetail = async (batchId: number): Promise<BatchProgressDetail> => {
  const response = await apiClient.get<BatchProgressDetail>(
    `/student/batches/${batchId}/progress/detail`,
  )
  return response.data
}

// ==================== Profile Setup ====================

export interface StudentProfileUpdateData {
  fullName: string
  phone: string
  degree: string
  branch: string
  year: number
  rollNumber: string
  bio?: string
  githubUrl?: string
  portfolioUrl?: string
  resumeUrl?: string
}

export interface StudentProfileResponse {
  id: number
  userId: number
  collegeId: number
  fullName: string
  rollNumber: string
  degree: string
  branch: string
  year: number
  phone: string
  githubUrl?: string
  portfolioUrl?: string
  resumeUrl?: string
  bio?: string
  accountStatus: string
  profileCompleted: boolean
  createdAt: string
  updatedAt: string
}

/**
 * Complete student profile setup (first-time login)
 */
export const completeProfile = async (
  data: StudentProfileUpdateData
): Promise<StudentProfileResponse> => {
  const response = await apiClient.put<StudentProfileResponse>(
    '/students/profile/complete',
    data
  )
  return response.data
}
