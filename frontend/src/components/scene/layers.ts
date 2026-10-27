import { useUiStore } from '@/lib/stores/ui-store'

export interface LayerDef {
  key: string
  label: string
}

export const BUILDINGS_LAYER = 'buildings-osm'

/** The layers listed in "All layers". Each replica ticket adds its own. */
export const LAYERS: LayerDef[] = [{ key: BUILDINGS_LAYER, label: 'Buildings (OSM)' }]

/** A layer shows unless it is switched off, or another layer is isolated. */
export function useLayerVisible(key: string): boolean {
  return useUiStore(
    (s) =>
      s.layerVisibility[key] !== false && (s.isolatedLayer === null || s.isolatedLayer === key),
  )
}
