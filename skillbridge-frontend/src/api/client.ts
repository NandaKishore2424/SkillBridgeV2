import axios from 'axios';

/**
 * Axios instance configured for SkillBridge API
 * 
 * Features:
 * - Base URL from environment variable
 * - Request interceptor: Adds JWT token to headers
 * - Response interceptor: Handles errors globally
 */
const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor: Add JWT token to every request
apiClient.interceptors.request.use(
  (config) => {
    // Get token from localStorage (consistent with AuthContext)
    const token = localStorage.getItem('skillbridge_access_token');
    
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

