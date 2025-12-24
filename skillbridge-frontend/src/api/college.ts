/**
 * College API endpoints
 */

import apiClient from './client'
import { College } from '@/shared/types'

/**
 * Get all active colleges (for registration form)
 */
export const getColleges = async (): Promise<College[]> => {
  const response = await apiClient.get<College[]>('/colleges/active')
  return response.data
}

