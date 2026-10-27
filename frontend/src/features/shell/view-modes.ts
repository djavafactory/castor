import type { Mode, ViewMode } from '@/lib/stores/ui-store'

/** Monitor is always the 3D replica; Design shows the 2D map in the 2D and Split views and the replica in 3D and Split. */
export function showsScene(mode: Mode, viewMode: ViewMode): boolean {
  return mode === 'monitor' || viewMode !== '2d'
}

export function showsMap(mode: Mode, viewMode: ViewMode): boolean {
  return mode === 'design' && viewMode !== '3d'
}
