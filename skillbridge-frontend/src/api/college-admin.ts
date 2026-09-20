/**
 * College Admin API endpoints
 * 
 * All endpoints require COLLEGE_ADMIN role
 * All operations are scoped to the admin's college
 */

import { idempotencyHeaders } from '@/lib/idempotency'
import apiClient from './client'
import type { Batch, BatchStatus, Student } from '@/shared/types'

// Re-exported: pages import BatchStatus from this module alongside the API
// functions that use it, and TS2459 forbids re-exporting an imported name
// implicitly.
export type { BatchStatus } from '@/shared/types'

export interface PagedResponse<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

// ==================== Dashboard ====================

/**
 * Exactly what `DashboardController.getDashboardStats` returns: five counts.
 *
 * It used to declare an optional `recentActivity` array as well. Nothing in the
 * backend has ever produced one -- the string appears nowhere in the Java -- so
 * the dashboard's "Recent Activity" card was unreachable, and being optional
 * meant no type error and no failing test ever said so. An optional field is a
 * claim that the server *might* send it; this one was a claim that it might not
 * have been written yet.
 */
export interface DashboardStats {
  totalBatches: number
  activeBatches: number
  totalStudents: number
  totalTrainers: number
  totalCompanies: number
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

/**
 * Filters applied server-side by the admin list endpoints.
 *
 * Each endpoint accepts the subset that makes sense for it -- `status` on
 * batches, `active` on people, `hiringType` on companies -- and ignores the
 * rest, so one type covers all four call sites.
 */
export interface ListFilters {
  search?: string
  /** Batches: UPCOMING / OPEN / ACTIVE / COMPLETED / CANCELLED. */
  status?: string
  /** Students and trainers: filters on the login's active flag. */
  active?: boolean
  /** Companies: FULL_TIME / INTERNSHIP / BOTH. */
  hiringType?: string
  /** `field` or `field,asc` / `field,desc`; unknown fields are a 400. */
  sort?: string
}

/**
 * Batches for the admin list.
 *
 * `search` and `status` go to the server rather than being applied to `items`
 * afterwards. Filtering the page you already have only ever searches that page:
 * before this, a batch on page 3 could not be found from page 1.
 */
export const getBatches = async (
  page = 0,
  size = 20,
  filters: ListFilters = {},
): Promise<PagedResponse<BatchWithDetails>> => {
  const response = await apiClient.get<PagedResponse<BatchWithDetails>>('/admin/batches', {
    params: { page, size, ...omitEmpty(filters) },
  })
  return response.data
}

/**
 * Drops blank filter values so they never reach the query string.
 *
 * `?search=` is not the same request as no `search` at all: an empty string
 * would be sent, and every endpoint would have to defend against it. Cheaper to
 * not send it.
 */
export function omitEmpty(filters: ListFilters): ListFilters {
  // `false` is a real value for `active` and must survive; only undefined,
  // null and the empty string are dropped.
  return Object.fromEntries(
    Object.entries(filters).filter(([, v]) => v !== undefined && v !== null && v !== ''),
  )
}

export const getBatchById = async (id: number): Promise<BatchWithDetails> => {
  const response = await apiClient.get<BatchWithDetails>(`/admin/batches/${id}`)
  return response.data
}

/**
 * Requires an idempotency key: `batches` has no unique constraint, so a repeated
 * request would create a second identical row. Get the key from
 * `useIdempotencyKey` so a retry reuses it -- a freshly generated one per call
 * would defeat the purpose. See `src/lib/idempotency.ts`.
 */
export const createBatch = async (
  data: CreateBatchRequest,
  idempotencyKey: string
): Promise<Batch> => {
  const response = await apiClient.post<Batch>('/admin/batches', data, idempotencyHeaders(idempotencyKey))
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
  collegeId?: number  // Optional: for SYSTEM_ADMIN to specify which college
  hiringProcess?: string
  notes?: string
}

export const getCompanies = async (
  page = 0,
  size = 20,
  filters: ListFilters = {},
): Promise<PagedResponse<Company>> => {
  const response = await apiClient.get<PagedResponse<Company>>('/admin/companies', {
    params: { page, size, ...omitEmpty(filters) },
  })
  return response.data
}

export const getCompanyById = async (id: number): Promise<Company> => {
  const response = await apiClient.get<Company>(`/admin/companies/${id}`)
  return response.data
}

/** Requires an idempotency key, for the same reason as {@link createBatch}. */
export const createCompany = async (
  data: CreateCompanyRequest,
  idempotencyKey: string
): Promise<Company> => {
  const response = await apiClient.post<Company>('/admin/companies', data, idempotencyHeaders(idempotencyKey))
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

export const getTrainers = async (
  page = 0,
  size = 20,
  filters: ListFilters = {},
): Promise<PagedResponse<Trainer>> => {
  const response = await apiClient.get<PagedResponse<Trainer>>('/admin/trainers', {
    params: { page, size, ...omitEmpty(filters) },
  })
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
  userId: number
  email: string
  fullName: string
  isActive?: boolean  // From user entity via DTO mapping
  enrolledBatchIds?: number[]
}

export const getStudents = async (
  page = 0,
  size = 20,
  filters: ListFilters = {},
): Promise<PagedResponse<StudentWithDetails>> => {
  const response = await apiClient.get<PagedResponse<StudentWithDetails>>('/admin/students', {
    params: { page, size, ...omitEmpty(filters) },
  })
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

/**
 * Approve or reject a pending enrollment REQUEST.
 *
 * These called PATCH /admin/batches/{batchId}/enrollments/{id}/approve, which
 * the backend has never exposed. The real endpoints are addressed by request id
 * alone -- a request already knows its batch, so passing one was never needed --
 * and are POST, not PATCH. The batchId parameter is gone rather than ignored,
 * so a caller cannot pass one and believe it matters.
 */
export const approveEnrollment = async (requestId: number): Promise<void> => {
  await apiClient.post(`/admin/enrollment-requests/${requestId}/approve`)
}

export const rejectEnrollment = async (requestId: number, reason?: string): Promise<void> => {
  await apiClient.post(`/admin/enrollment-requests/${requestId}/reject`, reason ? { reason } : {})
}

