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
  accountStatus?: string;
  profileCompleted?: boolean;
  /**
   * True while the account still holds the temporary password it was created
   * with. The backend rejects every request from such an account except the
   * password-change endpoints, so the UI must route to /first-login rather
   * than into the app.
   */
  mustChangePassword?: boolean;
}

// College types
export interface College {
  id: number;
  name: string;
  code: string;
  email?: string;
  phone?: string;
  status: 'ACTIVE' | 'INACTIVE';
  /** Sent by the backend; the college detail page renders it. */
  address?: string
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

