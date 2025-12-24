/**
 * Authentication-related TypeScript types
 */

import { User, UserRole } from './index'

/**
 * Authentication state
 */
export interface AuthState {
  user: User | null
  accessToken: string | null
  refreshToken: string | null
  isAuthenticated: boolean
  isLoading: boolean
  error: string | null
}

/**
 * Login credentials
 */
export interface LoginCredentials {
  email: string
  password: string
}

/**
 * Registration data for students/trainers
 */
export interface RegisterData {
  email: string
  password: string
  confirmPassword: string
  role: 'STUDENT' | 'TRAINER'
  collegeId: number
  // Student-specific fields
  fullName?: string
  rollNumber?: string
  degree?: string
  branch?: string
  year?: number
  // Trainer-specific fields
  department?: string
  specialization?: string
  bio?: string
}

/**
 * Auth response from API
 */
export interface AuthResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
  user: User
}

/**
 * Token refresh response
 */
export interface RefreshTokenResponse {
  accessToken: string
  refreshToken?: string
  expiresIn: number
}

/**
 * Auth context value
 */
export interface AuthContextValue {
  // State
  user: User | null
  isAuthenticated: boolean
  isLoading: boolean
  error: string | null

  // Actions
  login: (credentials: LoginCredentials) => Promise<void>
  register: (data: RegisterData) => Promise<void>
  logout: () => Promise<void>
  refreshAccessToken: () => Promise<void>
  clearError: () => void
}

