import { envResult } from './env'

/**
 * The end-to-end tests read positions from the live scene (gizmo parts, the 3D probe). The hooks exist in the dev server
 * and in every build except production, so they work against the dev stack and never ship to users.
 */
export function testHooksEnabled(): boolean {
  return import.meta.env.DEV || (envResult.ok && envResult.env.VITE_APP_ENV !== 'prod')
}
