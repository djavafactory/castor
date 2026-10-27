import { envResult } from './env'

/** Container yards, STS cranes, RTGs and TEU figures (VITE_FEATURE_CONTAINERS, on unless set to false). */
export function containersEnabled(): boolean {
  return envResult.ok ? envResult.env.VITE_FEATURE_CONTAINERS : true
}
