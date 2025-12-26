import apiClient from './client'


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

export const getStudentUploadHistory = async (): Promise<BulkUploadHistory[]> => {
    const response = await apiClient.get<BulkUploadHistory[]>('/admin/students/bulk-upload/history')
    return response.data
}

export const getTrainerUploadHistory = async (): Promise<BulkUploadHistory[]> => {
    const response = await apiClient.get<BulkUploadHistory[]>('/admin/trainers/bulk-upload/history')
    return response.data
}

export const resendStudentInvitation = async (id: number): Promise<void> => {
    await apiClient.post(`/admin/students/${id}/resend-invitation`)
}

export const resendTrainerInvitation = async (id: number): Promise<void> => {
    await apiClient.post(`/admin/trainers/${id}/resend-invitation`)
}
