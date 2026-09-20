/**
 * System Admin API endpoints
 * 
 * All endpoints require SYSTEM_ADMIN role
 */

import apiClient from './client'
import type { College } from '@/shared/types'
import type { BatchWithDetails, StudentWithDetails, Trainer } from './college-admin'
import type { PagedResponse, PageParams } from './paging'

// Re-exported: pages import College from this module alongside the API
// functions that use it, and TS2459 forbids re-exporting an imported name
// implicitly.
export type { College } from '@/shared/types'

export interface CreateCollegeRequest {
  name: string
  code: string
  email?: string
  phone?: string
  address?: string
}

export interface CreateCollegeAdminRequest {
  email: string
  password: string
  fullName: string
  phone?: string
}

export interface CreateCollegeAdminResponse {
  id: number
  email: string
  fullName: string
  collegeId: number
}

/**
 * Get all colleges (System Admin only)
 */
export const getAllColleges = async (
  params: PageParams = {},
): Promise<PagedResponse<College>> => {
  const response = await apiClient.get<PagedResponse<College>>('/admin/colleges', { params })
  return response.data
}

/**
 * Get college by ID
 */
export const getCollegeById = async (id: number): Promise<College> => {
  const response = await apiClient.get<College>(`/admin/colleges/${id}`)
  return response.data
}

/**
 * Create a new college
 */
export const createCollege = async (data: CreateCollegeRequest): Promise<College> => {
  const response = await apiClient.post<College>('/admin/colleges', data)
  return response.data
}

/**
 * Update college
 */
export const updateCollege = async (
  id: number,
  data: Partial<CreateCollegeRequest>
): Promise<College> => {
  const response = await apiClient.put<College>(`/admin/colleges/${id}`, data)
  return response.data
}

/**
 * Deactivate/Activate college
 */
export const updateCollegeStatus = async (
  id: number,
  status: 'ACTIVE' | 'INACTIVE'
): Promise<College> => {
  const response = await apiClient.patch<College>(`/admin/colleges/${id}/status`, { status })
  return response.data
}

/**
 * Create college admin for a college
 */
export const createCollegeAdmin = async (
  collegeId: number,
  data: CreateCollegeAdminRequest
): Promise<CreateCollegeAdminResponse> => {
  const response = await apiClient.post<CreateCollegeAdminResponse>(
    `/admin/colleges/${collegeId}/admins`,
    data
  )
  return response.data
}

/*
 * The four sub-resource reads behind the college detail screen.
 *
 * They were typed `PagedResponse<any>`, and the screen consumed them with
 * `student.name || student.fullName || '-'` -- a guess, written because the
 * type said nothing. `CollegeController` returns `StudentDTO`, `BatchDTO`,
 * `TrainerDTO` and `CollegeAdminResponse`, which are the four types named
 * below; `.name` has never existed on any of them.
 */

/**
 * Get students for a specific college
 */
export const getCollegeStudents = async (
  collegeId: number,
  params: PageParams = {},
): Promise<PagedResponse<StudentWithDetails>> => {
  const response = await apiClient.get<PagedResponse<StudentWithDetails>>(
    `/admin/colleges/${collegeId}/students`, { params })
  return response.data
}

/**
 * Get batches for a specific college
 */
export const getCollegeBatches = async (
  collegeId: number,
  params: PageParams = {},
): Promise<PagedResponse<BatchWithDetails>> => {
  const response = await apiClient.get<PagedResponse<BatchWithDetails>>(
    `/admin/colleges/${collegeId}/batches`, { params })
  return response.data
}

/**
 * Get trainers for a specific college
 */
export const getCollegeTrainers = async (
  collegeId: number,
  params: PageParams = {},
): Promise<PagedResponse<Trainer>> => {
  const response = await apiClient.get<PagedResponse<Trainer>>(
    `/admin/colleges/${collegeId}/trainers`, { params })
  return response.data
}

/**
 * Get admins for a specific college
 */
export const getCollegeAdmins = async (
  collegeId: number,
  params: PageParams = {},
): Promise<PagedResponse<CreateCollegeAdminResponse>> => {
  const response = await apiClient.get<PagedResponse<CreateCollegeAdminResponse>>(
    `/admin/colleges/${collegeId}/admins`, { params })
  return response.data
}

