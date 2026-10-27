import { toast } from 'sonner'
import { containersEnabled } from '@/lib/features'
import { useUiStore } from '@/lib/stores/ui-store'

export interface LayerDef {
  key: string
  label: string
  /** Read-only reference data (OpenStreetMap): it cannot be locked. */
  reference?: boolean
}

export const BUILDINGS_LAYER = 'buildings-osm'
export const ROADS_LAYER = 'roads-osm'
export const CONTAINERS_LAYER = 'containers'

export const LAYER_LOCKED_MESSAGE = 'That layer is locked. Unlock it in Layers to edit.'

/** Every layer of "All layers", in the order of the popover. */
const LAYERS: readonly LayerDef[] = [
  { key: 'terminals', label: 'Terminals' },
  { key: 'surface-areas', label: 'Surface areas' },
  { key: 'roads', label: 'Roads' },
  { key: 'storage-pads', label: 'Storage pads' },
  { key: 'buildings', label: 'Buildings and warehouses' },
  { key: BUILDINGS_LAYER, label: 'Buildings (OSM)', reference: true },
  { key: ROADS_LAYER, label: 'Roads (OSM)', reference: true },
  { key: 'berths', label: 'Berths' },
  { key: 'quay-cranes', label: 'Quay cranes' },
  { key: 'marine', label: 'Marine / berth' },
  { key: 'safety', label: 'Land / safety' },
  { key: 'storage', label: 'Storage' },
  { key: 'dry-bulk', label: 'Dry bulk' },
  { key: 'liquid-bulk', label: 'Liquid bulk' },
  { key: 'roro', label: 'RoRo' },
  { key: CONTAINERS_LAYER, label: 'Container terminal' },
  { key: 'transport', label: 'Transport' },
  { key: 'mobile', label: 'Mobile equipment' },
]

/** The layers listed in "All layers"; the container one needs its feature. */
export function layers(): LayerDef[] {
  return LAYERS.filter((l) => l.key !== CONTAINERS_LAYER || containersEnabled())
}

/** Types with a layer of their own; every other type follows its asset_type.category. */
const TYPE_LAYER: Record<string, string> = {
  TERMINAL_BOUNDARY: 'terminals',
  SURFACE_AREA: 'surface-areas',
  ROAD_SEGMENT: 'roads',
  INTERNAL_ROAD: 'roads',
  JUNCTION: 'roads',
  STORAGE_PAD: 'storage-pads',
  WAREHOUSE: 'buildings',
  WAREHOUSE_POLY: 'buildings',
  TRANSIT_SHED: 'buildings',
  COVERED_BAY: 'buildings',
  BUILDING: 'buildings',
  BERTH: 'berths',
  QUAY_CRANE_RAIL: 'quay-cranes',
}

const CATEGORY_LAYER: Record<string, string> = {
  MARINE: 'marine',
  LIFTING: 'quay-cranes',
  STORAGE: 'storage',
  DRY_BULK: 'dry-bulk',
  LIQUID_BULK: 'liquid-bulk',
  RORO: 'roro',
  CONTAINER: CONTAINERS_LAYER,
  TRANSPORT: 'transport',
  MOBILE: 'mobile',
  SAFETY: 'safety',
  ZONE: 'safety',
}

/** The layer an asset belongs to: by its type, else by asset_type.category. Undefined: no layer, always shown. */
export function layerOf(typeCode: string, category: string | undefined): string | undefined {
  return TYPE_LAYER[typeCode] ?? (category ? CATEGORY_LAYER[category] : undefined)
}

interface LayerState {
  layerVisibility: Record<string, boolean>
  isolatedLayer: string | null
}

/** A layer shows unless it is switched off, or another layer is isolated. */
export function layerShown(state: LayerState, key: string): boolean {
  return (
    state.layerVisibility[key] !== false &&
    (state.isolatedLayer === null || state.isolatedLayer === key)
  )
}

export function useLayerVisible(key: string): boolean {
  return useUiStore((s) => layerShown(s, key))
}

/** The asset types of a layout by code, mapped to their category. */
export function categoryMap(types: readonly { code: string; category: string }[] | undefined) {
  return new Map((types ?? []).map((t) => [t.code, t.category]))
}

/** The assets whose layer shows: a hidden layer is not drawn, and so cannot be hit either. */
export function visibleFeatures<T extends { properties: { type: string } }>(
  features: readonly T[],
  types: readonly { code: string; category: string }[] | undefined,
  state: LayerState,
): T[] {
  const categories = categoryMap(types)
  return features.filter((f) => {
    const key = layerOf(f.properties.type, categories.get(f.properties.type))
    return key === undefined || layerShown(state, key)
  })
}

/** Whether an asset of this type sits on a layer locked in "All layers". */
export function layerLocked(typeCode: string, category: string | undefined): boolean {
  const key = layerOf(typeCode, category)
  return key !== undefined && useUiStore.getState().lockedLayers[key] === true
}

/** Edit guard of Design: false, with the message as a toast, when the layer of the asset is locked. */
export function guardLayerEdit(typeCode: string, category: string | undefined): boolean {
  if (!layerLocked(typeCode, category)) return true
  toast(LAYER_LOCKED_MESSAGE)
  return false
}
