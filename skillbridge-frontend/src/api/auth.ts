import apiClient from './client';

/**
 * Authentication API endpoints
 */

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  collegeId: number;
  role: 'STUDENT' | 'TRAINER';
  // Student-specific fields
  fullName?: string;
  rollNumber?: string;
  degree?: string;
  branch?: string;
  year?: number;
  // Trainer-specific fields
  department?: string;
  specialization?: string;
  bio?: string;
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
  };
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
export const register = async (data: RegisterRequest): Promise<AuthResponse> => {
  const response = await apiClient.post<AuthResponse>('/auth/register', data);
  return response.data;
};

/**
 * Refresh access token
 */
export const refreshToken = async (refreshToken: string): Promise<AuthResponse> => {
  const response = await apiClient.post<AuthResponse>('/auth/refresh', {
    refreshToken,
  });
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
  }
};

/**
 * Get current user information
 */
export const getCurrentUser = async (): Promise<any> => {
  const response = await apiClient.get('/auth/me');
  return response.data;
};

