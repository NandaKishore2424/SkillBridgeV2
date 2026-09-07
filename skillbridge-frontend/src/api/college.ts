/**
 * College API endpoints
 */

import apiClient from './client'
import type { College } from '@/shared/types'
import type { PagedResponse, PageParams } from './paging'

/**
 * Get all active colleges (for registration form)
 */
export const getColleges = async (
  params: PageParams = {},
): Promise<PagedResponse<College>> => {
  const response = await apiClient.get<PagedResponse<College>>('/colleges/active', { params })
  return response.data
}

