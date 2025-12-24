/**
 * Shared TypeScript types for SkillBridge frontend
 */

// User types
export type UserRole = 'SYSTEM_ADMIN' | 'COLLEGE_ADMIN' | 'TRAINER' | 'STUDENT';

export interface User {
  id: number;
  email: string;
  role: UserRole;
  collegeId?: number;
  isActive: boolean;
}

// College types
export interface College {
  id: number;
  name: string;
  code: string;
  email?: string;
  phone?: string;
  status: 'ACTIVE' | 'INACTIVE';
}

// Batch types
export type BatchStatus = 'UPCOMING' | 'OPEN' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED';

export interface Batch {
  id: number;
  collegeId: number;
  name: string;
  description?: string;
  status: BatchStatus;
  startDate?: string;
  endDate?: string;
}

// Student types
export interface Student {
  id: number;
  userId: number;
  collegeId: number;
  rollNumber: string;
  degree?: string;
  branch?: string;
  year?: number;
}

// API Response types
export interface ApiResponse<T> {
  data: T;
  message?: string;
}

export interface ApiError {
  error: {
    code: string;
    message: string;
    timestamp: string;
  };
}

