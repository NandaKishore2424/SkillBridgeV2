/**
 * Student API endpoints
 * 
 * For student role operations
 */

import apiClient from './client'
import type { Batch, BatchStatus } from '@/shared/types'

// ==================== Student Dashboard ====================

export interface StudentDashboardStats {
  enrolledBatches: number
  activeBatches: number
  completedBatches: number
  totalTopicsCompleted: number
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

export interface StudentProgressTopic {
  id: number
  title: string
  description?: string
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'NEEDS_IMPROVEMENT'
  feedback?: string
  updatedAt?: string
}

export const getStudentDashboardStats = async (): Promise<StudentDashboardStats> => {
  const response = await apiClient.get<StudentDashboardStats>('/student/dashboard/stats')
  return response.data
}

export const getRecommendedBatches = async (): Promise<RecommendedBatch[]> => {
  const response = await apiClient.get<RecommendedBatch[]>('/student/batches/recommended')
  return response.data
}

export const getAllAvailableBatches = async (): Promise<Batch[]> => {
  const response = await apiClient.get<Batch[]>('/student/batches/available')
  return response.data
}

export const getStudentBatches = async (): Promise<StudentBatch[]> => {
  const response = await apiClient.get<StudentBatch[]>('/student/batches')
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

export const applyToBatch = async (batchId: number): Promise<EnrollmentResponse> => {
  const response = await apiClient.post<EnrollmentResponse>('/student/batches/apply', {
    batchId,
  })
  return response.data
}

// ==================== Progress Tracking ====================

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

