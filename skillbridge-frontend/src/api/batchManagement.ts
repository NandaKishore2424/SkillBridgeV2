import apiClient from './client';

// ============================================================================
// Batch API
// ============================================================================

export const batchApi = {
    // Get all batches
    getAllBatches: () => apiClient.get('/trainer/batches'),

    // Get batch by ID
    getBatchById: (batchId: number) => apiClient.get(`/batches/${batchId}`),
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
        topics?: Array<{
            name: string;
            description?: string;
            displayOrder: number;
        }>;
    }) =>
        apiClient.post(`/batches/${batchId}/syllabus/modules`, data),

    // Update a module
    updateModule: (moduleId: number, data: {
        name?: string;
        description?: string;
        displayOrder?: number;
    }) =>
        apiClient.put(`/syllabus/modules/${moduleId}`, data),

    // Delete a module
    deleteModule: (moduleId: number) =>
        apiClient.delete(`/syllabus/modules/${moduleId}`),

    // Add topic to module
    addTopic: (moduleId: number, data: {
        name: string;
        description?: string;
        displayOrder: number;
    }) =>
        apiClient.post(`/syllabus/modules/${moduleId}/topics`, data),

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
// Timeline API
// ============================================================================

export const timelineApi = {
    // Get timeline for a batch
    getTimeline: (batchId: number) =>
        apiClient.get(`/batches/${batchId}/timeline`),

    // Create a session
    createSession: (batchId: number, data: {
        sessionNumber: number;
        title: string;
        description?: string;
        topicId?: number;
        plannedDate?: string;
    }) =>
        apiClient.post(`/batches/${batchId}/timeline/sessions`, data),

    // Update a session
    updateSession: (sessionId: number, data: {
        sessionNumber?: number;
        title?: string;
        description?: string;
        topicId?: number;
        plannedDate?: string;
    }) =>
        apiClient.put(`/timeline/sessions/${sessionId}`, data),

    // Delete a session
    deleteSession: (sessionId: number) =>
        apiClient.delete(`/timeline/sessions/${sessionId}`),
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

    // Get pending requests
    getPendingRequests: () =>
        apiClient.get('/admin/enrollment-requests/pending'),

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

    // Get trainer's own requests
    getMyRequests: () =>
        apiClient.get('/trainer/enrollment-requests'),
};

// ============================================================================
// Types
// ============================================================================

export interface SyllabusModule {
    id: number;
    name: string;
    description?: string;
    displayOrder: number;
    topics: SyllabusTopic[];
    topicsCount: number;
    completedTopicsCount: number;
}

export interface SyllabusTopic {
    id: number;
    name: string;
    description?: string;
    displayOrder: number;
    isCompleted: boolean;
    completedAt?: string;
}

export interface TimelineSession {
    id: number;
    sessionNumber: number;
    title: string;
    description?: string;
    topicId?: number;
    topicName?: string;
    plannedDate?: string;
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
