import apiClient from './client'
import type { PagedResponse, PageParams } from './paging'

/** Which kind of account a CSV creates; also the URL segment. */
export type ImportKind = 'students' | 'trainers'

/**
 * The answer to an upload. The import runs after this returns, so row
 * outcomes are fetched separately (getUploadRows). `alreadyUploaded` means
 * this exact file was uploaded before and `uploadId` is that upload.
 */
export interface BulkUploadResponse {
    uploadId: number
    status: string
    rows: number
    alreadyUploaded: boolean
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
    entityType: string
}

export interface BulkUploadDetail {
    id: number
    entityType: string
    fileName: string
    status: 'PROCESSING' | 'COMPLETED' | 'FAILED' | string
    totalRows: number
    /** Accounts created, including those whose invitation email failed. */
    successfulRows: number
    failedRows: number
    emailFailedRows: number
    /** Why the whole upload failed, if it did. */
    errorReport: string | null
    createdAt: string
    completedAt: string | null
}

export type RowStatus = 'SUCCESS' | 'EMAIL_FAILED' | 'FAILED'

export interface BulkUploadRow {
    /** As a spreadsheet shows it: the header is row 1. */
    rowNumber: number
    status: RowStatus
    message: string | null
    values: Record<string, string>
    /** The account created, for "Resend invitation". */
    userId: number | null
}

export const uploadCsv = async (kind: ImportKind, file: File): Promise<BulkUploadResponse> => {
    const formData = new FormData()
    formData.append('file', file)
    const response = await apiClient.post<BulkUploadResponse>(`/admin/${kind}/bulk-upload`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
    })
    return response.data
}

export const downloadTemplate = async (kind: ImportKind): Promise<Blob> => {
    const response = await apiClient.get(`/admin/${kind}/bulk-upload/template`, { responseType: 'blob' })
    return response.data
}

/**
 * Upload history, newest first, paged.
 *
 * `bulk_uploads` is append-only -- a row per upload, never removed -- so this
 * is one of the fastest-growing lists in the product.
 */
export const getUploadHistory = async (
    kind: ImportKind,
    params: PageParams = {},
): Promise<PagedResponse<BulkUploadHistory>> => {
    const response = await apiClient.get<PagedResponse<BulkUploadHistory>>(
        `/admin/${kind}/bulk-upload/history`, { params })
    return response.data
}

export const getUpload = async (uploadId: number): Promise<BulkUploadDetail> => {
    const response = await apiClient.get<BulkUploadDetail>(`/admin/bulk-uploads/${uploadId}`)
    return response.data
}

/** Rows needing attention by default: FAILED and EMAIL_FAILED, in file order. */
export const getUploadRows = async (
    uploadId: number,
    statuses: RowStatus[] = ['FAILED', 'EMAIL_FAILED'],
    params: PageParams = {},
): Promise<PagedResponse<BulkUploadRow>> => {
    const response = await apiClient.get<PagedResponse<BulkUploadRow>>(
        `/admin/bulk-uploads/${uploadId}/rows`, { params: { ...params, status: statuses.join(',') } })
    return response.data
}

/** 502 EMAIL_NOT_SENT means the new password was set but the mail provider refused it. */
export const resendInvitation = async (kind: ImportKind, userId: number): Promise<void> => {
    await apiClient.post(`/admin/${kind}/${userId}/resend-invitation`)
}
