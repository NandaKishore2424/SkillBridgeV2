import apiClient from './client';
import type { User } from '@/shared/types'

/**
 * Authentication API endpoints
 */

export interface LoginRequest {
  email: string;
  password: string;
}


export interface AuthResponse {
  accessToken: string;
  // No refreshToken: the API sets it as an HttpOnly cookie and never sends it in a body.
  expiresIn: number;
  user?: {
    id: number;
    email: string;
    role: string;
    collegeId?: number;
    isActive: boolean;
    accountStatus?: string;
    profileCompleted?: boolean;
    mustChangePassword?: boolean;
  };
}

export interface FirstLoginRequest {
  email: string;
  temporaryPassword: string;
  newPassword: string;
}

/**
 * Login user
 */
export const login = async (credentials: LoginRequest): Promise<AuthResponse> => {
  const response = await apiClient.post<AuthResponse>('/auth/login', credentials);
  return response.data;
};

/**
 * Register new user
 */

/**
 * Refresh access token
 */
export const refreshToken = async (): Promise<AuthResponse> => {
  // The refresh token travels only in the HttpOnly cookie (withCredentials).
  const response = await apiClient.post<AuthResponse>('/auth/refresh', {});
  return response.data;
};

/**
 * Logout user (revoke refresh token)
 */
export const logout = async (): Promise<void> => {
  try {
    // The server revokes the refresh token from the cookie and clears it.
    await apiClient.post('/auth/logout', {});
  } catch (error) {
    // Continue with logout even if the API call fails
    console.error('Logout API call failed:', error);
  }
};

/**
 * Get current user information
 */
export const getCurrentUser = async (): Promise<User> => {
  const response = await apiClient.get<User>('/auth/me');
  return response.data;
};

/**
 * Exchange a temporary password for a real one.
 *
 * <p>Returns a fresh token pair, so the caller is signed in afterwards and does
 * not have to log in a second time.
 */
export const firstLogin = async (data: FirstLoginRequest): Promise<AuthResponse> => {
  const response = await apiClient.post<AuthResponse>('/auth/first-login', data);
  return response.data;
};
