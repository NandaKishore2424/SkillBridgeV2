import apiClient from './client';

/**
 * Authentication API endpoints
 */

export interface LoginRequest {
  email: string;
  password: string;
}


export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
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
export const refreshToken = async (refreshToken?: string | null): Promise<AuthResponse> => {
  const payload = refreshToken ? { refreshToken } : {};
  const response = await apiClient.post<AuthResponse>('/auth/refresh', payload);
  return response.data;
};

/**
 * Logout user (revoke refresh token)
 */
export const logout = async (): Promise<void> => {
  const refreshToken = localStorage.getItem('skillbridge_refresh_token');
  if (refreshToken) {
    try {
      await apiClient.post('/auth/logout', { refreshToken });
    } catch (error) {
      // Continue with logout even if API call fails
      console.error('Logout API call failed:', error);
    }
  } else {
    try {
      await apiClient.post('/auth/logout', {});
    } catch (error) {
      console.error('Logout API call failed:', error);
    }
  }
};

/**
 * Get current user information
 */
export const getCurrentUser = async (): Promise<any> => {
  const response = await apiClient.get('/auth/me');
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
