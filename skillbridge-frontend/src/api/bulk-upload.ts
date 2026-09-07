import apiClient from './client'
import type { PagedResponse, PageParams } from './paging'


export interface BulkUploadResponse {
    uploadId: number
    totalRows: number
    successfulRows: number
    failedRows: number
    errors: UploadError[]
    status: string
}

export interface UploadError {
    rowNumber: number
    errorMessage: string
    rowData: Record<string, string>
}

export interface BulkUploadHistory {
    id: number
    fileName: string
    totalRows: number
    successfulRows: number
    failedRows: number
    status: string
    createdAt: string
    completedAt: string
    errorReport: string
    entityType: string
}

export const uploadStudents = async (file: File): Promise<BulkUploadResponse> => {
    const formData = new FormData()
    formData.append('file', file)

    const response = await apiClient.post<BulkUploadResponse>(
        '/admin/students/bulk-upload',
        formData,
        {
            headers: {
                'Content-Type': 'multipart/form-data',
            },
        }
    )
    return response.data
}

export const uploadTrainers = async (file: File): Promise<BulkUploadResponse> => {
    const formData = new FormData()
    formData.append('file', file)

    const response = await apiClient.post<BulkUploadResponse>(
        '/admin/trainers/bulk-upload',
        formData,
        {
            headers: {
                'Content-Type': 'multipart/form-data',
            },
        }
    )
    return response.data
}

export const downloadStudentTemplate = async (): Promise<Blob> => {
    const response = await apiClient.get('/admin/students/bulk-upload/template', {
        responseType: 'blob',
    })
    return response.data
}

export const downloadTrainerTemplate = async (): Promise<Blob> => {
    const response = await apiClient.get('/admin/trainers/bulk-upload/template', {
        responseType: 'blob',
    })
    return response.data
}

/**
 * Upload history, newest first, paged.
 *
 * `bulk_uploads` is append-only -- a row per upload, never removed -- so this
 * is one of the fastest-growing lists in the product.
 */
export const getStudentUploadHistory = async (
    params: PageParams = {},
): Promise<PagedResponse<BulkUploadHistory>> => {
    const response = await apiClient.get<PagedResponse<BulkUploadHistory>>(
        '/admin/students/bulk-upload/history', { params })
    return response.data
}

export const getTrainerUploadHistory = async (
    params: PageParams = {},
): Promise<PagedResponse<BulkUploadHistory>> => {
    const response = await apiClient.get<PagedResponse<BulkUploadHistory>>(
        '/admin/trainers/bulk-upload/history', { params })
    return response.data
}

export const resendStudentInvitation = async (id: number): Promise<void> => {
    await apiClient.post(`/admin/students/${id}/resend-invitation`)
}

export const resendTrainerInvitation = async (id: number): Promise<void> => {
    await apiClient.post(`/admin/trainers/${id}/resend-invitation`)
}
