import { describe, expect, it } from 'vitest';

import apiClient, { DEFAULT_API_BASE_URL } from './client';

describe('the API client', () => {
  it('calls its own origin unless told otherwise', () => {
    // A relative base is what keeps the refresh cookie first-party: Vercel's
    // rewrite and Vite's dev proxy both forward /api from the page's origin.
    // An absolute default would be baked into a Vercel build, which sets no
    // variables, and send every visitor's browser to that address instead.
    expect(DEFAULT_API_BASE_URL.startsWith('/')).toBe(true);
    expect(DEFAULT_API_BASE_URL).not.toMatch(/^\/\//);
  });

  it('uses that default when no base URL is configured', () => {
    // The suite runs with VITE_API_BASE_URL unset, as the Vercel build does.
    expect(import.meta.env.VITE_API_BASE_URL).toBeUndefined();
    expect(apiClient.defaults.baseURL).toBe(DEFAULT_API_BASE_URL);
  });

  it('sends cookies, so the refresh token reaches /auth/refresh', () => {
    expect(apiClient.defaults.withCredentials).toBe(true);
  });
});
