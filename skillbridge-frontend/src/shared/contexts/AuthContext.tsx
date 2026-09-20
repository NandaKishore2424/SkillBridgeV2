/**
 * Authentication Context
 * 
 * Manages authentication state and provides authentication methods
 * throughout the application.
 * 
 * Features:
 * - User state management
 * - Token storage (access token in memory only, refresh token via HttpOnly cookie)
 * - Login/logout functions
 * - Token refresh logic
 * - Automatic token refresh on 401 errors
 */

import { useState, useEffect, useCallback, useRef, type ReactNode } from 'react'

import { AuthContext } from './authContextObject'
import { useNavigate } from 'react-router-dom'
import type { AuthContextValue, AuthState, LoginCredentials } from '@/shared/types/auth'
import type { User, UserRole } from '@/shared/types'
import * as authAPI from '@/api/auth'
import apiClient from '@/api/client'
import { clearAccessToken, getAccessToken, onTokenFromAnotherTab, setAccessToken } from '@/shared/auth/accessTokenStore'
import { dashboardPathFor } from '@/shared/auth/dashboardPath'
import { apiErrorMessage } from '@/lib/apiError'

// Create context with undefined default (will be set by Provider)


// Neither token is in localStorage any more. The access token lives in memory
// (shared/auth/accessTokenStore); the refresh token only in the HttpOnly cookie
// the API sets, which script cannot read. These two keys remain so that
// clearAuthState deletes what older builds left behind.
const LEGACY_ACCESS_TOKEN_KEY = 'skillbridge_access_token'
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
 * The claims this application reads out of an access token.
 *
 * Only `exp` is used, and only to decide whether to refresh before making a
 * request -- the backend re-validates everything. Typed rather than `any` so
 * that a second reader cannot quietly start trusting a claim: the authorities
 * come from the token on the server side, and treating a client-side decode as
 * authoritative is how a role check ends up in the browser.
 */
interface TokenClaims {
  /** Expiry, seconds since the epoch. */
  exp?: number
  /** The user id, as the subject. */
  sub?: string
  userId?: string
  email?: string
  role?: string
  collegeId?: number
  isActive?: boolean
}

/**
 * Decode JWT token to extract payload
 * Returns null if token is not a JWT
 */
function decodeJWT(token: string): TokenClaims | null {
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
        // A reload empties memory, so there is never a token here. The refresh
        // cookie is what restores the session; see the else branch.
        const storedAccessToken = getAccessToken()
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
          } catch {
            // Expected for anyone who is simply not signed in.
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
   * What another tab announces: a token it has just been issued, or null when it
   * signed out. Applied without announcing again, or the tabs would echo.
   */
  useEffect(() => onTokenFromAnotherTab((token) => {
    setAccessToken(token, false)
    setState((previous) => token
      ? { ...previous, accessToken: token, isAuthenticated: true, isLoading: false }
      : { ...previous, user: null, accessToken: null, isAuthenticated: false, isLoading: false })
  }), [])

  /**
   * Clear authentication state
   */
  const clearAuthState = () => {
    clearAccessToken()
    localStorage.removeItem(LEGACY_ACCESS_TOKEN_KEY)
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

    // In memory, and announced to the other tabs.
    setAccessToken(response.accessToken)
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

        // A student who has not finished their profile goes there first;
        // everybody else goes to their dashboard. The table is in
        // `shared/auth/dashboardPath` so that this is the only special case
        // rather than a fifth copy of the mapping.
        //
        // The `default` this replaced sent an unrecognised role to
        // `/dashboard`, which has never been a route: the catch-all turned it
        // into a silent redirect to the public page, so a successful login
        // looked like a failed one.
        if (user.role === 'STUDENT' && (user.accountStatus === 'PENDING_SETUP' || !user.profileCompleted)) {
          redirectPath = '/student/profile-setup'
        } else {
          redirectPath = dashboardPathFor(user.role)
        }
        console.log('[AuthContext] Navigating to:', redirectPath)
        navigate(redirectPath, { replace: true })
      } else {
        console.error('[AuthContext] No user after login - this should not happen')
      }
    } catch (error: unknown) {
      console.error('[AuthContext] Login error:', error)
      const errorMessage = apiErrorMessage(error, 'Could not sign you in. Try again.')
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
      const tokenBefore = getAccessToken()
      try {
        // No token argument: the browser sends the HttpOnly refresh cookie.
        const response = await authAPI.refreshToken()
        await handleAuthSuccess(response)
      } catch (error) {
        // Tabs share one cookie jar. If another tab refreshed first, our cookie
        // was already rotated and the server refused it (within its grace period,
        // without ending the session) -- but that tab announced its new token on
        // the BroadcastChannel, and we applied it. Use it rather than logging out.
        const tokenNow = getAccessToken()
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
            const newToken = getAccessToken()
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
