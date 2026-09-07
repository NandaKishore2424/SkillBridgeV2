import apiClient from './client';
import type { PagedResponse, PageParams } from './paging';

// ============================================================================
// Batch API
// ============================================================================

export const batchApi = {
    // Get all batches
    getAllBatches: () => apiClient.get('/trainer/batches'),

    // Get batch by ID (use admin endpoint for now, TODO: create trainer-specific endpoint)
    getBatchById: (batchId: number) => apiClient.get(`/admin/batches/${batchId}`),
};

// ============================================================================
// Syllabus API
// ============================================================================

export const syllabusApi = {
    // Get syllabus for a batch
    getSyllabus: (batchId: number) =>
        apiClient.get(`/batches/${batchId}/syllabus`),

    // Create a module
    createModule: (batchId: number, data: {
        name: string;
        description?: string;
        displayOrder: number;
        startDate?: string;
        endDate?: string;
        submodules?: Array<{
            name: string;
            description?: string;
            displayOrder: number;
            startDate?: string;
            endDate?: string;
            weekNumber?: number;
            topics?: Array<{
                name: string;
                description?: string;
                displayOrder: number;
            }>;
        }>;
    }) =>
        apiClient.post(`/batches/${batchId}/syllabus/modules`, data),

    // Update a module
    updateModule: (moduleId: number, data: {
        name?: string;
        description?: string;
        displayOrder?: number;
        startDate?: string;
        endDate?: string;
    }) =>
        apiClient.put(`/syllabus/modules/${moduleId}`, data),

    // Delete a module
    deleteModule: (moduleId: number) =>
        apiClient.delete(`/syllabus/modules/${moduleId}`),

    // Create a submodule
    createSubmodule: (moduleId: number, data: {
        name: string;
        description?: string;
        displayOrder: number;
        startDate?: string;
        endDate?: string;
        weekNumber?: number;
        topics?: Array<{
            name: string;
            description?: string;
            displayOrder: number;
        }>;
    }) =>
        apiClient.post(`/syllabus/modules/${moduleId}/submodules`, data),

    // Update a submodule
    updateSubmodule: (submoduleId: number, data: {
        name?: string;
        description?: string;
        displayOrder?: number;
        startDate?: string;
        endDate?: string;
        weekNumber?: number;
    }) =>
        apiClient.put(`/syllabus/submodules/${submoduleId}`, data),

    // Delete a submodule
    deleteSubmodule: (submoduleId: number) =>
        apiClient.delete(`/syllabus/submodules/${submoduleId}`),

    // Add topic to submodule
    addTopic: (submoduleId: number, data: {
        name: string;
        description?: string;
        displayOrder: number;
    }) =>
        apiClient.post(`/syllabus/submodules/${submoduleId}/topics`, data),

    // Update a topic
    updateTopic: (topicId: number, data: {
        name?: string;
        description?: string;
        displayOrder?: number;
    }) =>
        apiClient.put(`/syllabus/topics/${topicId}`, data),

    // Delete a topic
    deleteTopic: (topicId: number) =>
        apiClient.delete(`/syllabus/topics/${topicId}`),

    // Toggle topic completion
    toggleTopicCompletion: (topicId: number) =>
        apiClient.post(`/syllabus/topics/${topicId}/toggle-completion`),

    // Copy syllabus from another batch
    copySyllabus: (targetBatchId: number, sourceBatchId: number) =>
        apiClient.post(`/batches/${targetBatchId}/syllabus/copy-from/${sourceBatchId}`),
};


// ============================================================================
// Enrollment API (Admin)
// ============================================================================

export const enrollmentApi = {
    // Get batch enrollments
    getBatchEnrollments: (batchId: number) =>
        apiClient.get(`/admin/batches/${batchId}/enrollments`),

    // Enroll a student
    enrollStudent: (batchId: number, studentId: number) =>
        apiClient.post(`/admin/batches/${batchId}/enrollments/${studentId}`),

    // Remove a student
    removeStudent: (batchId: number, studentId: number) =>
        apiClient.delete(`/admin/batches/${batchId}/enrollments/${studentId}`),

    // Pending requests, newest first. Paged: the queue grows with the college,
    // and with the whole platform for a SYSTEM_ADMIN.
    getPendingRequests: (params: PageParams = {}) =>
        apiClient.get<PagedResponse<EnrollmentRequestData>>(
            '/admin/enrollment-requests/pending', { params }),

    // Approve request
    approveRequest: (requestId: number) =>
        apiClient.post(`/admin/enrollment-requests/${requestId}/approve`),

    // Reject request
    rejectRequest: (requestId: number) =>
        apiClient.post(`/admin/enrollment-requests/${requestId}/reject`),
};

// ============================================================================
// Trainer Enrollment Request API
// ============================================================================

export const trainerEnrollmentApi = {
    // Create enrollment request
    createRequest: (data: {
        batchId: number;
        studentId: number;
        requestType: 'ADD' | 'REMOVE';
        reason?: string;
    }) =>
        apiClient.post('/trainer/enrollment-requests', data),

    // The calling trainer's own pending requests, newest first. Paged.
    getMyRequests: (params: PageParams = {}) =>
        apiClient.get<PagedResponse<EnrollmentRequestData>>(
            '/trainer/enrollment-requests', { params }),
};

// ============================================================================
// Types
// ============================================================================

export interface SyllabusTopic {
    id: number;
    name: string;
    description?: string;
    displayOrder: number;
    isCompleted: boolean;
    completedAt?: string;
}

export interface SyllabusSubmodule {
    id: number;
    name: string;
    description?: string;
    displayOrder: number;
    startDate?: string;
    endDate?: string;
    weekNumber?: number;
    topics: SyllabusTopic[];
    topicsCount: number;
    completedTopicsCount: number;
}

export interface SyllabusModule {
    id: number;
    name: string;
    description?: string;
    displayOrder: number;
    startDate?: string;
    endDate?: string;
    submodules: SyllabusSubmodule[];
    submodulesCount: number;
    totalTopicsCount: number;
    completedTopicsCount: number;
}


export interface EnrolledStudent {
    studentId: number;
    fullName: string;
    rollNumber: string;
    email: string;
    department: string;
    year: number;
}

export interface BatchEnrollment {
    batchId: number;
    batchName: string;
    enrolledStudents: EnrolledStudent[];
    enrolledCount: number;
}

export interface EnrollmentRequestData {
    id: number;
    batchId: number;
    batchName: string;
    studentId: number;
    studentName: string;
    studentRollNumber: string;
    trainerId: number;
    trainerName: string;
    requestType: 'ADD' | 'REMOVE';
    status: 'PENDING' | 'APPROVED' | 'REJECTED';
    reason?: string;
    reviewedBy?: number;
    reviewedByName?: string;
    reviewedAt?: string;
    createdAt: string;
}
