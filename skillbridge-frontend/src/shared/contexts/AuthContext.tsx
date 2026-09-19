/**
 * Authentication Context
 * 
 * Manages authentication state and provides authentication methods
 * throughout the application.
 * 
 * Features:
 * - User state management
 * - Token storage (access token in memory + localStorage, refresh token via HttpOnly cookie)
 * - Login/logout functions
 * - Token refresh logic
 * - Automatic token refresh on 401 errors
 */

import { createContext, useContext, useState, useEffect, useCallback, useRef, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import type { AuthContextValue, AuthState, LoginCredentials } from '@/shared/types/auth'
import type { User, UserRole } from '@/shared/types'
import * as authAPI from '@/api/auth'
import apiClient from '@/api/client'

// Create context with undefined default (will be set by Provider)
const AuthContext = createContext<AuthContextValue | undefined>(undefined)

// Token storage keys
const ACCESS_TOKEN_KEY = 'skillbridge_access_token'
// The refresh token lives ONLY in the HttpOnly cookie the API sets, where
// script cannot read it. Older builds kept a copy in localStorage; this key is
// kept solely so clearAuthState can delete such a leftover.
const LEGACY_REFRESH_TOKEN_KEY = 'skillbridge_refresh_token'
const USER_KEY = 'skillbridge_user'

interface AuthProviderProps {
  children: ReactNode
}

/**
 * Check if token is a JWT (has 3 parts separated by dots)
 */
function isJWT(token: string): boolean {
  if (!token) return false
  const parts = token.split('.')
  return parts.length === 3
}

/**
 * Decode JWT token to extract payload
 * Returns null if token is not a JWT
 */
function decodeJWT(token: string): any {
  if (!token || !isJWT(token)) {
    return null // Not a JWT, return null
  }

  try {
    const base64Url = token.split('.')[1]
    if (!base64Url) return null

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
 * For non-JWT tokens, assume they're valid (they'll be validated by backend)
 */
function isTokenExpired(token: string): boolean {
  if (!token) return true
  if (!isJWT(token)) return false // Non-JWT tokens are considered valid (backend will validate)

  const decoded = decodeJWT(token)
  if (!decoded || !decoded.exp) return true
  return decoded.exp * 1000 < Date.now()
}

/**
 * Extract user from token
 * Returns null if token is not a JWT (user should come from API response instead)
 */
function getUserFromToken(token: string): User | null {
  if (!isJWT(token)) {
    return null // Not a JWT, user should come from API response
  }

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
        const storedUser = localStorage.getItem(USER_KEY)

        if (storedAccessToken && !isTokenExpired(storedAccessToken)) {
          // Access token is valid
          // Try to get user from localStorage first, then from token (if JWT)
          let user: User | null = null
          if (storedUser) {
            try {
              user = JSON.parse(storedUser)
            } catch (e) {
              console.error('Error parsing stored user:', e)
            }
          }

          // If no user in localStorage and token is JWT, try to extract from token
          if (!user && isJWT(storedAccessToken)) {
            user = getUserFromToken(storedAccessToken)
          }

          // If we have a token but no user, still consider authenticated (user will be fetched from API if needed)
          if (user || storedAccessToken) {
            setState({
              user,
              accessToken: storedAccessToken,
              refreshToken: null,
              isAuthenticated: true,
              isLoading: false,
              error: null,
            })
          } else {
            clearAuthState()
          }
        } else {
          // No usable access token. The refresh cookie, if the browser holds one,
          // restores the session; if not, this 401s and the user logs in.
          try {
            const response = await authAPI.refreshToken()
            await handleAuthSuccess(response)
          } catch (error) {
            clearAuthState()
          }
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
    localStorage.removeItem(LEGACY_REFRESH_TOKEN_KEY)
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
    // Priority: response.user > token (if JWT) > null
    let user: User | null = null

    // First, try to get user from response (backend provides this)
    if (response.user) {
      user = {
        id: response.user.id,
        email: response.user.email,
        role: response.user.role as UserRole,
        collegeId: response.user.collegeId,
        isActive: response.user.isActive !== false,
        accountStatus: response.user.accountStatus,
        profileCompleted: response.user.profileCompleted,
        mustChangePassword: response.user.mustChangePassword,
      }
    } else if (isJWT(response.accessToken)) {
      // Fallback: try to extract from JWT token if it's a JWT
      user = getUserFromToken(response.accessToken)
    }

    // Store tokens
    localStorage.setItem(ACCESS_TOKEN_KEY, response.accessToken)
    if (user) {
      localStorage.setItem(USER_KEY, JSON.stringify(user))
    }

    // Update state
    setState({
      user,
      accessToken: response.accessToken,
      refreshToken: null,
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
      console.log('[AuthContext] Login started')
      const response = await authAPI.login(credentials)
      console.log('[AuthContext] Login response:', response)

      const user = await handleAuthSuccess(response)
      console.log('[AuthContext] User after handleAuthSuccess:', user)

      // Redirect based on role
      if (user) {
        let redirectPath = '/'

        // An account still on its temporary password can reach nothing but the
        // password-change endpoints -- the backend answers everything else with
        // 403 PASSWORD_CHANGE_REQUIRED. Sending it to a role dashboard produces
        // a page of "Failed to load" panels and no way out, which is exactly
        // what happened before this check existed.
        if (user.mustChangePassword) {
          navigate('/first-login', { replace: true, state: { email: user.email } })
          return
        }

        switch (user.role) {
          case 'SYSTEM_ADMIN':
            redirectPath = '/admin/dashboard'
            break
          case 'COLLEGE_ADMIN':
            redirectPath = '/admin/college-admin/dashboard'
            break
          case 'TRAINER':
            redirectPath = '/trainer/dashboard'
            break
          case 'STUDENT':
            // Check if student needs to complete profile setup
            if (user.accountStatus === 'PENDING_SETUP' || !user.profileCompleted) {
              redirectPath = '/student/profile-setup'
            } else {
              redirectPath = '/student/dashboard'
            }
            break
          default:
            redirectPath = '/dashboard'
        }
        console.log('[AuthContext] Navigating to:', redirectPath)
        navigate(redirectPath, { replace: true })
      } else {
        console.error('[AuthContext] No user after login - this should not happen')
      }
    } catch (error: any) {
      console.error('[AuthContext] Login error:', error)
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

  // Registration removed 2026-09-06: the product is invite-only. Accounts are
  // provisioned by a college admin through bulk upload and activated through
  // /auth/first-login, and POST /auth/register was never implemented on the
  // backend -- the page had been posting into the void. See HANDOVER Q1.

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
  /**
   * Refreshes the access token, at most once at a time.
   *
   * **The single-flight guard is load-bearing, not an optimisation.** The server
   * rotates refresh tokens: a successful refresh revokes the one it was given.
   * A page that fires several requests at once — every dashboard here does —
   * gets several 401s the moment the access token expires, and without this
   * guard each one would call refresh with the *same* refresh cookie. The first
   * succeeds and rotates it; the rest present the revoked one, which the server
   * treats as a replayed token, and the catch below logs the user out.
   *
   * That was survivable while access tokens lasted an hour. They now last
   * fifteen minutes (the token is authoritative for authorisation, so its
   * lifetime is the revocation window), so the race would be four times as
   * frequent. Concurrent callers share one in-flight refresh instead.
   */
  const refreshInFlight = useRef<Promise<void> | null>(null)

  const refreshAccessToken = useCallback(async () => {
    if (refreshInFlight.current) {
      return refreshInFlight.current
    }

    const attempt = (async () => {
      const tokenBefore = localStorage.getItem(ACCESS_TOKEN_KEY)
      try {
        // No token argument: the browser sends the HttpOnly refresh cookie.
        const response = await authAPI.refreshToken()
        await handleAuthSuccess(response)
      } catch (error) {
        // Tabs share one cookie jar and one localStorage. If another tab
        // refreshed first, our cookie was already rotated and the server refused
        // it (within its grace period, without ending the session), but that
        // tab has stored a new access token. Use it rather than logging out.
        const tokenNow = localStorage.getItem(ACCESS_TOKEN_KEY)
        if (tokenNow && tokenNow !== tokenBefore) {
          return
        }
        clearAuthState()
        navigate('/login')
        throw error
      }
    })()

    refreshInFlight.current = attempt
    try {
      return await attempt
    } finally {
      refreshInFlight.current = null
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

        // 401 means the access token is missing, expired or revoked: refresh
        // once and retry. 403 means "not allowed" and is final. The auth
        // endpoints themselves are excluded -- a 401 from /auth/refresh must
        // end the session, not trigger another refresh.
        const url: string = originalRequest?.url ?? ''
        const isAuthEndpoint = /\/auth\/(login|refresh|first-login|logout)/.test(url)
        if (error.response?.status === 401 && !originalRequest._retry && !isAuthEndpoint) {
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

