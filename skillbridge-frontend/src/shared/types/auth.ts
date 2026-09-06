/**
 * Authentication-related TypeScript types
 */

import type { User } from './index'

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
  logout: () => Promise<void>
  refreshAccessToken: () => Promise<void>
  clearError: () => void
}

