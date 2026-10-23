import { create } from 'zustand'
import type { components } from '@/types/api'

export type Role = components['schemas']['Role']

export interface AuthSession {
  username: string
  displayName: string
  roles: Role[]
  /** Full `Authorization` header value, e.g. `Basic dXNlcjpwYXNz`. */
  credential: string
}

interface AuthState {
  username: string | null
  displayName: string | null
  roles: Role[]
  credential: string | null
  signIn: (session: AuthSession) => void
  clearSession: () => void
}

/** Builds a Basic credential; UTF-8 safe. */
export function basicCredential(username: string, password: string): string {
  const bytes = new TextEncoder().encode(`${username}:${password}`)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return `Basic ${btoa(binary)}`
}

const emptySession = { username: null, displayName: null, roles: [], credential: null }

/**
 * Memory only. No persist middleware: credentials are never written to localStorage,
 * sessionStorage, cookies or IndexedDB, so a page reload asks for login again.
 */
export const useAuthStore = create<AuthState>()((set) => ({
  ...emptySession,
  signIn: (session) => set({ ...session }),
  clearSession: () => set({ ...emptySession }),
}))
