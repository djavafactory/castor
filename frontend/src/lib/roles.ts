import type { Role } from './stores/auth-store'

/** Design mode needs EDITOR; ADMIN can do everything. */
export function canDesign(roles: readonly Role[]): boolean {
  return roles.includes('EDITOR') || roles.includes('ADMIN')
}

/** Publishing, restoring and discarding need PUBLISHER; ADMIN can do everything. */
export function canPublish(roles: readonly Role[]): boolean {
  return roles.includes('PUBLISHER') || roles.includes('ADMIN')
}
