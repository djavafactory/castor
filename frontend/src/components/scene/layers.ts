import { containersEnabled } from '@/lib/features'
import { useUiStore } from '@/lib/stores/ui-store'

export interface LayerDef {
  key: string
  label: string
}

export const BUILDINGS_LAYER = 'buildings-osm'
export const ROADS_LAYER = 'roads-osm'
export const CONTAINERS_LAYER = 'containers'

/** The layers listed in "All layers". Each replica ticket adds its own; the container one needs its feature. */
export function layers(): LayerDef[] {
  return [
    { key: BUILDINGS_LAYER, label: 'Buildings (OSM)' },
    { key: ROADS_LAYER, label: 'Roads (OSM)' },
    ...(containersEnabled() ? [{ key: CONTAINERS_LAYER, label: 'Containers' }] : []),
  ]
}

/** A layer shows unless it is switched off, or another layer is isolated. */
export function useLayerVisible(key: string): boolean {
  return useUiStore(
    (s) =>
      s.layerVisibility[key] !== false && (s.isolatedLayer === null || s.isolatedLayer === key),
  )
}
