import { apiGet, apiRequest } from '@/lib/api-client'
import type { Role } from '@/lib/stores/auth-store'

export interface MeResponse {
  username: string
  displayName: string
  roles: Role[]
}

export interface DemoUser {
  username: string
  label: string
}

/** Signs in: the Basic header is sent for this one call; a 401 here means wrong credentials, not an ended session. */
export function fetchMe(credential: string): Promise<MeResponse> {
  return apiGet<MeResponse>('/v1/me', {
    headers: { Authorization: credential },
    handleUnauthorized: false,
  }).then((r) => r.data)
}

export function fetchDemoUsers(signal?: AbortSignal): Promise<DemoUser[]> {
  return apiGet<DemoUser[]>('/v1/auth/demo-users', {
    handleUnauthorized: false,
    signal,
  }).then((r) => r.data)
}

/** Best effort: records the LOGOUT event. Signing out never waits for or depends on it. */
export async function reportLogout(): Promise<void> {
  try {
    await apiRequest('/v1/auth/logout', {
      method: 'POST',
      handleUnauthorized: false,
      timeoutMs: 3000,
    })
  } catch {
    // The credentials are dropped from memory either way.
  }
}
