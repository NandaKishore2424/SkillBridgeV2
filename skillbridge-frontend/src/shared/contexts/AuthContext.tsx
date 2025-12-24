/**
 * Authentication Context
 * 
 * Manages authentication state and provides authentication methods
 * throughout the application.
 * 
 * Features:
 * - User state management
 * - Token storage (access token in memory + localStorage, refresh token in localStorage)
 * - Login/logout/register functions
 * - Token refresh logic
 * - Automatic token refresh on 401 errors
 */

import { createContext, useContext, useState, useEffect, useCallback, ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { AuthContextValue, AuthState, LoginCredentials, RegisterData } from '@/shared/types/auth'
import { User, UserRole } from '@/shared/types'
import * as authAPI from '@/api/auth'
import apiClient from '@/api/client'

// Create context with undefined default (will be set by Provider)
const AuthContext = createContext<AuthContextValue | undefined>(undefined)

// Token storage keys
const ACCESS_TOKEN_KEY = 'skillbridge_access_token'
const REFRESH_TOKEN_KEY = 'skillbridge_refresh_token'
const USER_KEY = 'skillbridge_user'

interface AuthProviderProps {
  children: ReactNode
}

/**
 * Decode JWT token to extract payload
 */
function decodeJWT(token: string): any {
  try {
    const base64Url = token.split('.')[1]
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/')
    const jsonPayload = decodeURIComponent(
      atob(base64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    )
    return JSON.parse(jsonPayload)
  } catch (error) {
    console.error('Error decoding JWT:', error)
    return null
  }
}

/**
 * Check if token is expired
 */
function isTokenExpired(token: string): boolean {
  const decoded = decodeJWT(token)
  if (!decoded || !decoded.exp) return true
  return decoded.exp * 1000 < Date.now()
}

/**
 * Extract user from token
 */
function getUserFromToken(token: string): User | null {
  const decoded = decodeJWT(token)
  if (!decoded) return null

  return {
    id: parseInt(decoded.sub || decoded.userId || '0'),
    email: decoded.email || '',
    role: decoded.role as UserRole,
    collegeId: decoded.collegeId || undefined,
    isActive: decoded.isActive !== false,
  }
}

export function AuthProvider({ children }: AuthProviderProps) {
  const navigate = useNavigate()
  const [state, setState] = useState<AuthState>({
    user: null,
    accessToken: null,
    refreshToken: null,
    isAuthenticated: false,
    isLoading: true,
    error: null,
  })

  /**
   * Initialize auth state from localStorage
   */
  useEffect(() => {
    const initializeAuth = async () => {
      try {
        const storedAccessToken = localStorage.getItem(ACCESS_TOKEN_KEY)
        const storedRefreshToken = localStorage.getItem(REFRESH_TOKEN_KEY)
        const storedUser = localStorage.getItem(USER_KEY)

        if (storedAccessToken && !isTokenExpired(storedAccessToken)) {
          // Access token is valid
          const user = storedUser ? JSON.parse(storedUser) : getUserFromToken(storedAccessToken)
          setState({
            user,
            accessToken: storedAccessToken,
            refreshToken: storedRefreshToken,
            isAuthenticated: true,
            isLoading: false,
            error: null,
          })
        } else if (storedRefreshToken) {
          // Access token expired, try to refresh
          try {
            const response = await authAPI.refreshToken(storedRefreshToken)
            await handleAuthSuccess(response)
          } catch (error) {
            // Refresh failed, clear everything
            clearAuthState()
          }
        } else {
          // No tokens, user is not authenticated
          clearAuthState()
        }
      } catch (error) {
        console.error('Error initializing auth:', error)
        clearAuthState()
      }
    }

    initializeAuth()
  }, [])

  /**
   * Clear authentication state
   */
  const clearAuthState = () => {
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
    setState({
      user: null,
      accessToken: null,
      refreshToken: null,
      isAuthenticated: false,
      isLoading: false,
      error: null,
    })
  }

  /**
   * Handle successful authentication
   */
  const handleAuthSuccess = async (response: authAPI.AuthResponse): Promise<User | null> => {
    // Try to get user from token first, fallback to response.user
    let user: User | null = getUserFromToken(response.accessToken)
    
    // If user not in token, try response.user
    if (!user && response.user) {
      user = {
        id: response.user.id,
        email: response.user.email,
        role: response.user.role as UserRole,
        collegeId: response.user.collegeId,
        isActive: response.user.isActive,
      }
    }

    // Store tokens
    localStorage.setItem(ACCESS_TOKEN_KEY, response.accessToken)
    localStorage.setItem(REFRESH_TOKEN_KEY, response.refreshToken)
    if (user) {
      localStorage.setItem(USER_KEY, JSON.stringify(user))
    }

    // Update state
    setState({
      user,
      accessToken: response.accessToken,
      refreshToken: response.refreshToken,
      isAuthenticated: true,
      isLoading: false,
      error: null,
    })

    return user
  }

  /**
   * Login function
   */
  const login = useCallback(async (credentials: LoginCredentials) => {
    setState((prev) => ({ ...prev, isLoading: true, error: null }))

    try {
      const response = await authAPI.login(credentials)
      const user = await handleAuthSuccess(response)

      // Redirect based on role
      if (user) {
        switch (user.role) {
          case 'SYSTEM_ADMIN':
            navigate('/admin/colleges')
            break
          case 'COLLEGE_ADMIN':
            navigate('/admin/dashboard')
            break
          case 'TRAINER':
            navigate('/trainer/dashboard')
            break
          case 'STUDENT':
            navigate('/student/dashboard')
            break
          default:
            navigate('/dashboard')
        }
      }
    } catch (error: any) {
      const errorMessage =
        error.response?.data?.message || error.message || 'Login failed. Please try again.'
      setState((prev) => ({
        ...prev,
        isLoading: false,
        error: errorMessage,
        isAuthenticated: false,
      }))
      throw error
    }
  }, [navigate])

  /**
   * Register function (for students/trainers)
   */
  const register = useCallback(async (data: RegisterData) => {
    setState((prev) => ({ ...prev, isLoading: true, error: null }))

    try {
      // Prepare registration payload
      const registerPayload: authAPI.RegisterRequest = {
        email: data.email,
        password: data.password,
        role: data.role,
        collegeId: data.collegeId,
      }

      const response = await authAPI.register(registerPayload)
      await handleAuthSuccess(response)

      // Redirect to login (user should login after registration)
      navigate('/login', { state: { message: 'Registration successful! Please login.' } })
    } catch (error: any) {
      const errorMessage =
        error.response?.data?.message || error.message || 'Registration failed. Please try again.'
      setState((prev) => ({
        ...prev,
        isLoading: false,
        error: errorMessage,
        isAuthenticated: false,
      }))
      throw error
    }
  }, [navigate])

  /**
   * Logout function
   */
  const logout = useCallback(async () => {
    try {
      // Call logout API to revoke refresh token
      await authAPI.logout()
    } catch (error) {
      console.error('Error during logout:', error)
      // Continue with logout even if API call fails
    } finally {
      clearAuthState()
      navigate('/login')
    }
  }, [navigate])

  /**
   * Refresh access token
   */
  const refreshAccessToken = useCallback(async () => {
    const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY)
    if (!refreshToken) {
      throw new Error('No refresh token available')
    }

    try {
      const response = await authAPI.refreshToken(refreshToken)
      await handleAuthSuccess(response)
    } catch (error) {
      // Refresh failed, logout user
      clearAuthState()
      navigate('/login')
      throw error
    }
  }, [navigate])

  /**
   * Clear error
   */
  const clearError = useCallback(() => {
    setState((prev) => ({ ...prev, error: null }))
  }, [])

  // Set up token refresh interceptor
  useEffect(() => {
    const interceptor = apiClient.interceptors.response.use(
      (response) => response,
      async (error) => {
        const originalRequest = error.config

        // If 401 and not already retried, try to refresh token
        if (error.response?.status === 401 && !originalRequest._retry) {
          originalRequest._retry = true

          try {
            await refreshAccessToken()
            // Retry original request with new token
            const newToken = localStorage.getItem(ACCESS_TOKEN_KEY)
            if (newToken) {
              originalRequest.headers.Authorization = `Bearer ${newToken}`
              return apiClient(originalRequest)
            }
          } catch (refreshError) {
            // Refresh failed, redirect to login
            clearAuthState()
            navigate('/login')
            return Promise.reject(refreshError)
          }
        }

        return Promise.reject(error)
      }
    )

    return () => {
      apiClient.interceptors.response.eject(interceptor)
    }
  }, [refreshAccessToken, navigate])

  const value: AuthContextValue = {
    user: state.user,
    isAuthenticated: state.isAuthenticated,
    isLoading: state.isLoading,
    error: state.error,
    login,
    register,
    logout,
    refreshAccessToken,
    clearError,
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

/**
 * Hook to use authentication context
 * 
 * @throws Error if used outside AuthProvider
 */
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}

