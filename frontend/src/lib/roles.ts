import type { Role } from './stores/auth-store'

/** Design mode needs EDITOR; ADMIN can do everything. */
export function canDesign(roles: readonly Role[]): boolean {
  return roles.includes('EDITOR') || roles.includes('ADMIN')
}

/** Publishing, restoring and discarding need PUBLISHER; ADMIN can do everything. */
export function canPublish(roles: readonly Role[]): boolean {
  return roles.includes('PUBLISHER') || roles.includes('ADMIN')
}

/** The Integrations page (sources, quarantine, mappings) is for OPERATOR; ADMIN can do everything. */
export function canViewIntegrations(roles: readonly Role[]): boolean {
  return roles.includes('OPERATOR') || roles.includes('ADMIN')
}

/** Replaying, keys and mappings change what the gateway does: ADMIN only. */
export function canAdministerIntegrations(roles: readonly Role[]): boolean {
  return roles.includes('ADMIN')
}
