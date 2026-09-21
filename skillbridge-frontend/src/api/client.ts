import axios from 'axios';

import { getAccessToken } from '@/shared/auth/accessTokenStore';

/**
 * Axios instance configured for SkillBridge API
 * 
 * Features:
 * - Base URL from environment variable
 * - Request interceptor: Adds JWT token to headers
 * - Response interceptor: Handles errors globally
 */
// Relative, and that is load-bearing. In production Vercel rewrites /api/* to
// the backend host, and locally Vite's dev server proxies /api to :8080, so the
// browser only ever talks to the origin that served the page and the refresh
// cookie stays first-party (VERCEL.md). The default used to be
// http://localhost:8080/api/v1: fine on a laptop, and on Vercel -- which sets
// no variables -- every visitor's browser would have called its own machine.
// client.test.ts holds it.
export const DEFAULT_API_BASE_URL = '/api/v1';

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || DEFAULT_API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true,
});

// Request interceptor: Add JWT token to every request
apiClient.interceptors.request.use(
  (config) => {
    // From memory, never localStorage: see shared/auth/accessTokenStore.
    const token = getAccessToken();

    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// Response interceptor: Handle errors globally
// Note: Token refresh logic is handled in AuthContext
apiClient.interceptors.response.use(
  (response) => {
    return response;
  },
  async (error) => {
    // 401 errors are handled by AuthContext's refresh token logic
    // We just pass the error through here
    return Promise.reject(error);
  }
);

export default apiClient;

