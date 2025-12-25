/**
 * System Admin API endpoints
 * 
 * All endpoints require SYSTEM_ADMIN role
 */

import apiClient from './client'
import type { College } from '@/shared/types'

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
export const getAllColleges = async (): Promise<College[]> => {
  const response = await apiClient.get<College[]>('/admin/colleges')
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

/**
 * Get students for a specific college
 */
export const getCollegeStudents = async (collegeId: number): Promise<any[]> => {
  const response = await apiClient.get<any[]>(`/admin/colleges/${collegeId}/students`)
  return response.data
}

/**
 * Get batches for a specific college
 */
export const getCollegeBatches = async (collegeId: number): Promise<any[]> => {
  const response = await apiClient.get<any[]>(`/admin/colleges/${collegeId}/batches`)
  return response.data
}

/**
 * Get trainers for a specific college
 */
export const getCollegeTrainers = async (collegeId: number): Promise<any[]> => {
  const response = await apiClient.get<any[]>(`/admin/colleges/${collegeId}/trainers`)
  return response.data
}

/**
 * Get admins for a specific college
 */
export const getCollegeAdmins = async (collegeId: number): Promise<any[]> => {
  const response = await apiClient.get<any[]>(`/admin/colleges/${collegeId}/admins`)
  return response.data
}

