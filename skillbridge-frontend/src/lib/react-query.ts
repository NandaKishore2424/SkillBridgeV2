import { QueryClient } from '@tanstack/react-query';

/**
 * React Query client configuration
 * 
 * Default options:
 * - staleTime: 5 minutes (data considered fresh for 5 min)
 * - cacheTime: 10 minutes (cached data kept for 10 min)
 * - retry: 1 time on failure
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5 * 60 * 1000, // 5 minutes
      gcTime: 10 * 60 * 1000, // 10 minutes (formerly cacheTime)
      retry: 1,
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: 1,
    },
  },
});

