/**
 * Batch Details API endpoints
 * 
 * Extended API for detailed batch management
 */

import apiClient from './client'
import type { BatchWithDetails, Trainer, Company } from './college-admin'

// ==================== Batch Details ====================

export interface BatchDetails extends BatchWithDetails {
  trainers: Trainer[]
  companies: Company[]
  /**
   * Enrolled student count, from BatchDTO. Drives the "Enrollments (n)" tab.
   *
   * The old `syllabus?: Syllabus` field went with the flat-syllabus API: BatchDTO
   * has never carried a syllabus, and the curriculum is fetched separately as a
   * module tree.
   */
  studentCount?: number
}

export const getBatchDetails = async (id: number): Promise<BatchDetails> => {
  const response = await apiClient.get<BatchDetails>(`/admin/batches/${id}`)
  return response.data
}

export const getAssignedTrainers = async (batchId: number): Promise<Trainer[]> => {
  const response = await apiClient.get<Trainer[]>(`/admin/batches/${batchId}/trainers`)
  return response.data
}

export const getAssignedCompanies = async (batchId: number): Promise<Company[]> => {
  const response = await apiClient.get<Company[]>(`/admin/batches/${batchId}/companies`)
  return response.data
}

// ==================== Trainer Assignment ====================

export interface AssignTrainersRequest {
  trainerIds: number[]
}

export const assignTrainersToBatch = async (
  batchId: number,
  trainerIds: number[]
): Promise<void> => {
  await apiClient.post(`/admin/batches/${batchId}/trainers`, { trainerIds })
}

export const unassignTrainerFromBatch = async (
  batchId: number,
  trainerId: number
): Promise<void> => {
  await apiClient.delete(`/admin/batches/${batchId}/trainers/${trainerId}`)
}

// ==================== Company Mapping ====================

export interface MapCompaniesRequest {
  companyIds: number[]
}

export const mapCompaniesToBatch = async (
  batchId: number,
  companyIds: number[]
): Promise<void> => {
  await apiClient.post(`/admin/batches/${batchId}/companies`, { companyIds })
}

export const unmapCompanyFromBatch = async (
  batchId: number,
  companyId: number
): Promise<void> => {
  await apiClient.delete(`/admin/batches/${batchId}/companies/${companyId}`)
}

// ==================== Syllabus Management ====================






// Re-export enrollment functions
export {
  getBatchEnrollments,
  approveEnrollment,
  rejectEnrollment,
} from './college-admin'
