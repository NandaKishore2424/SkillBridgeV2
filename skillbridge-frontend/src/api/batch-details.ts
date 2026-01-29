/**
 * Batch Details API endpoints
 * 
 * Extended API for detailed batch management
 */

import apiClient from './client'
import type { Batch, BatchStatus } from '@/shared/types'
import type { BatchWithDetails, BatchEnrollment, Trainer, Company } from './college-admin'

// ==================== Batch Details ====================

export interface BatchDetails extends BatchWithDetails {
  trainers: Trainer[]
  companies: Company[]
  syllabus?: Syllabus
}

export interface Syllabus {
  id: number
  batchId: number
  title: string
  description?: string
  topics: SyllabusTopic[]
}

export interface SyllabusTopic {
  id: number
  syllabusId: number
  title: string
  description?: string
  difficulty?: 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED'
  estimatedHours?: number
  order: number
}

export interface CreateSyllabusRequest {
  title: string
  description?: string
}

export interface CreateSyllabusTopicRequest {
  title: string
  description?: string
  difficulty?: 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED'
  estimatedHours?: number
  order: number
}

export interface UpdateSyllabusTopicRequest {
  title?: string
  description?: string
  difficulty?: 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED'
  estimatedHours?: number
  order?: number
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

export const createSyllabus = async (
  batchId: number,
  data: CreateSyllabusRequest
): Promise<Syllabus> => {
  const response = await apiClient.post<Syllabus>(`/admin/batches/${batchId}/syllabus`, data)
  return response.data
}

export const updateSyllabus = async (
  batchId: number,
  data: Partial<CreateSyllabusRequest>
): Promise<Syllabus> => {
  const response = await apiClient.put<Syllabus>(`/admin/batches/${batchId}/syllabus`, data)
  return response.data
}

export const addSyllabusTopic = async (
  batchId: number,
  data: CreateSyllabusTopicRequest
): Promise<SyllabusTopic> => {
  const response = await apiClient.post<SyllabusTopic>(
    `/admin/batches/${batchId}/syllabus/topics`,
    data
  )
  return response.data
}

export const updateSyllabusTopic = async (
  batchId: number,
  topicId: number,
  data: UpdateSyllabusTopicRequest
): Promise<SyllabusTopic> => {
  const response = await apiClient.put<SyllabusTopic>(
    `/admin/batches/${batchId}/syllabus/topics/${topicId}`,
    data
  )
  return response.data
}

export const deleteSyllabusTopic = async (batchId: number, topicId: number): Promise<void> => {
  await apiClient.delete(`/admin/batches/${batchId}/syllabus/topics/${topicId}`)
}

// Re-export enrollment functions
export {
  getBatchEnrollments,
  approveEnrollment,
  rejectEnrollment,
} from './college-admin'

