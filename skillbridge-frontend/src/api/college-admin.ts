/**
 * College Admin API endpoints
 * 
 * All endpoints require COLLEGE_ADMIN role
 * All operations are scoped to the admin's college
 */

import apiClient from './client'
import type { Batch, BatchStatus, College, Student } from '@/shared/types'

// ==================== Dashboard ====================

export interface DashboardStats {
  totalBatches: number
  activeBatches: number
  totalStudents: number
  totalTrainers: number
  totalCompanies: number
  recentActivity?: Array<{
    type: string
    message: string
    timestamp: string
  }>
}

export const getDashboardStats = async (): Promise<DashboardStats> => {
  const response = await apiClient.get<DashboardStats>('/admin/dashboard/stats')
  return response.data
}

// ==================== Batches ====================

export interface CreateBatchRequest {
  name: string
  description?: string
  startDate?: string
  endDate?: string
  maxEnrollments?: number
}

export interface BatchWithDetails extends Batch {
  enrolledCount: number
  trainerCount: number
  companyCount: number
}

export const getBatches = async (): Promise<BatchWithDetails[]> => {
  const response = await apiClient.get<BatchWithDetails[]>('/admin/batches')
  return response.data
}

export const getBatchById = async (id: number): Promise<BatchWithDetails> => {
  const response = await apiClient.get<BatchWithDetails>(`/admin/batches/${id}`)
  return response.data
}

export const createBatch = async (data: CreateBatchRequest): Promise<Batch> => {
  const response = await apiClient.post<Batch>('/admin/batches', data)
  return response.data
}

export const updateBatch = async (
  id: number,
  data: Partial<CreateBatchRequest>
): Promise<Batch> => {
  const response = await apiClient.put<Batch>(`/admin/batches/${id}`, data)
  return response.data
}

export const updateBatchStatus = async (
  id: number,
  status: BatchStatus
): Promise<Batch> => {
  const response = await apiClient.patch<Batch>(`/admin/batches/${id}/status`, { status })
  return response.data
}

// ==================== Companies ====================

export interface Company {
  id: number
  collegeId: number
  name: string
  domain?: string
  hiringType: 'FULL_TIME' | 'INTERNSHIP' | 'BOTH'
  hiringProcess?: string
  notes?: string
  linkedBatchIds?: number[]
}

export interface CreateCompanyRequest {
  name: string
  domain?: string
  hiringType: 'FULL_TIME' | 'INTERNSHIP' | 'BOTH'
  hiringProcess?: string
  notes?: string
}

export const getCompanies = async (): Promise<Company[]> => {
  const response = await apiClient.get<Company[]>('/admin/companies')
  return response.data
}

export const getCompanyById = async (id: number): Promise<Company> => {
  const response = await apiClient.get<Company>(`/admin/companies/${id}`)
  return response.data
}

export const createCompany = async (data: CreateCompanyRequest): Promise<Company> => {
  const response = await apiClient.post<Company>('/admin/companies', data)
  return response.data
}

export const updateCompany = async (
  id: number,
  data: Partial<CreateCompanyRequest>
): Promise<Company> => {
  const response = await apiClient.put<Company>(`/admin/companies/${id}`, data)
  return response.data
}

export const linkCompanyToBatch = async (
  companyId: number,
  batchId: number
): Promise<void> => {
  await apiClient.post(`/admin/companies/${companyId}/batches/${batchId}`)
}

export const unlinkCompanyFromBatch = async (
  companyId: number,
  batchId: number
): Promise<void> => {
  await apiClient.delete(`/admin/companies/${companyId}/batches/${batchId}`)
}

// ==================== Trainers ====================

export interface Trainer {
  id: number
  userId: number
  collegeId: number
  fullName: string
  email: string
  phone?: string
  department?: string
  specialization?: string
  bio?: string
  isActive: boolean
  assignedBatchIds?: number[]
}

export interface CreateTrainerRequest {
  email: string
  password: string
  fullName: string
  phone?: string
  department?: string
  specialization?: string
  bio?: string
}

export const getTrainers = async (): Promise<Trainer[]> => {
  const response = await apiClient.get<Trainer[]>('/admin/trainers')
  return response.data
}

export const getTrainerById = async (id: number): Promise<Trainer> => {
  const response = await apiClient.get<Trainer>(`/admin/trainers/${id}`)
  return response.data
}

export const createTrainer = async (data: CreateTrainerRequest): Promise<Trainer> => {
  const response = await apiClient.post<Trainer>('/admin/trainers', data)
  return response.data
}

export const updateTrainer = async (
  id: number,
  data: Partial<CreateTrainerRequest>
): Promise<Trainer> => {
  const response = await apiClient.put<Trainer>(`/admin/trainers/${id}`, data)
  return response.data
}

export const updateTrainerStatus = async (
  id: number,
  isActive: boolean
): Promise<Trainer> => {
  const response = await apiClient.patch<Trainer>(`/admin/trainers/${id}/status`, { isActive })
  return response.data
}

export const assignTrainerToBatch = async (
  trainerId: number,
  batchId: number
): Promise<void> => {
  await apiClient.post(`/admin/trainers/${trainerId}/batches/${batchId}`)
}

export const unassignTrainerFromBatch = async (
  trainerId: number,
  batchId: number
): Promise<void> => {
  await apiClient.delete(`/admin/trainers/${trainerId}/batches/${batchId}`)
}

// ==================== Students ====================

export interface StudentWithDetails extends Student {
  user: {
    id: number
    email: string
    isActive: boolean
  }
  enrolledBatchIds?: number[]
}

export const getStudents = async (): Promise<StudentWithDetails[]> => {
  const response = await apiClient.get<StudentWithDetails[]>('/admin/students')
  return response.data
}

export const getStudentById = async (id: number): Promise<StudentWithDetails> => {
  const response = await apiClient.get<StudentWithDetails>(`/admin/students/${id}`)
  return response.data
}

export const updateStudent = async (
  id: number,
  data: Partial<Pick<Student, 'rollNumber' | 'degree' | 'branch' | 'year'>>
): Promise<Student> => {
  const response = await apiClient.put<Student>(`/admin/students/${id}`, data)
  return response.data
}

export const updateStudentStatus = async (
  id: number,
  isActive: boolean
): Promise<Student> => {
  const response = await apiClient.patch<Student>(`/admin/students/${id}/status`, { isActive })
  return response.data
}

// ==================== Batch Enrollments ====================

export interface BatchEnrollment {
  id: number
  batchId: number
  studentId: number
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  appliedAt: string
  student?: StudentWithDetails
}

export const getBatchEnrollments = async (batchId: number): Promise<BatchEnrollment[]> => {
  const response = await apiClient.get<BatchEnrollment[]>(`/admin/batches/${batchId}/enrollments`)
  return response.data
}

export const approveEnrollment = async (
  batchId: number,
  enrollmentId: number
): Promise<void> => {
  await apiClient.patch(`/admin/batches/${batchId}/enrollments/${enrollmentId}/approve`)
}

export const rejectEnrollment = async (
  batchId: number,
  enrollmentId: number
): Promise<void> => {
  await apiClient.patch(`/admin/batches/${batchId}/enrollments/${enrollmentId}/reject`)
}

