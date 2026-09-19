import apiClient from './client'

/**
 * The AI skill-gap report (backend SkillGapController). Its document shape is
 * contracts/skill-gap-report/v1, which the AI service writes and the backend
 * reads; this is that document as the backend serves it.
 */
export interface MatchedJob {
    title: string
    company: string | null
    /** Cosine similarity, -1 to 1. */
    similarity: number
    matchedSkills: string[]
    missingSkills: string[]
}

export interface SkillGapReport {
    /** SKIPPED: the student has no skills to analyse. */
    status: 'SUCCESS' | 'SKIPPED'
    analyzedAt: string
    studentSkills: string[]
    matchedJobs: MatchedJob[]
    missingSkills: string[]
}

/** null: no analysis has run yet (204). */
export const getMySkillGap = async (): Promise<SkillGapReport | null> => {
    const response = await apiClient.get<SkillGapReport>('/students/me/skill-gap')
    return response.status === 204 ? null : response.data
}

/** Queues a fresh analysis; the report changes once the AI service has run. */
export const refreshMySkillGap = async (): Promise<void> => {
    await apiClient.post('/students/me/skill-gap/refresh')
}

export const getStudentSkillGap = async (studentId: number): Promise<SkillGapReport | null> => {
    const response = await apiClient.get<SkillGapReport>(`/admin/students/${studentId}/skill-gap`)
    return response.status === 204 ? null : response.data
}
