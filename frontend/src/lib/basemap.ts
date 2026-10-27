/** The background map of the designer (DESIGNER-02): the options, their rules and the per-user preference. */

export type Basemap = 'sentinel2' | 'none' | 'google'

export const DEFAULT_BASEMAP: Basemap = 'sentinel2'

export const BASEMAPS: readonly { value: Basemap; label: string }[] = [
  { value: 'sentinel2', label: 'Sentinel-2 (EOX)' },
  { value: 'none', label: 'None (grid)' },
  { value: 'google', label: 'Google Maps (lookup only)' },
]

/** Shown on the map while Google Maps is the basemap. */
export const GOOGLE_NOTE =
  'Google Maps is a locator only: its imagery cannot be traced into stored geometry.'

/** The toast that refuses a draw tool or a placement on Google (SETUP-03). */
export const GOOGLE_TOAST =
  'Google Maps (lookup / reference only): tracing into stored geometry is not allowed. Switch the basemap first.'

export function isBasemap(value: unknown): value is Basemap {
  return BASEMAPS.some((b) => b.value === value)
}

export function basemapLabel(basemap: Basemap): string {
  return BASEMAPS.find((b) => b.value === basemap)?.label ?? basemap
}

/** Whether stored geometry may be drawn on this basemap. */
export function mayTraceOn(basemap: Basemap): boolean {
  return basemap !== 'google'
}

export const MIN_TILE_ZOOM = 8
export const MAX_TILE_ZOOM = 14
/** Metres per pixel of a zoom 0 tile at the equator. */
const EQUATOR_M_PER_PX = 156543.03

/** Tile zoom whose pixels are about as large as the screen's: round(log2(156543.03 x cos(lat) x k)), clamped 8-14. */
export function tileZoomFor(latDeg: number, k: number): number {
  const z = Math.round(Math.log2(EQUATOR_M_PER_PX * Math.cos((latDeg * Math.PI) / 180) * k))
  return Math.min(MAX_TILE_ZOOM, Math.max(MIN_TILE_ZOOM, z))
}

export const GRID_STEPS_M = [10, 25, 50, 100, 250, 500, 1000] as const
export const GRID_MIN_PX = 40

/** The first grid spacing that is at least 40 px on screen; the widest when even that is closer. */
export function gridStepFor(k: number): number {
  return (
    GRID_STEPS_M.find((step) => step * k >= GRID_MIN_PX) ?? GRID_STEPS_M[GRID_STEPS_M.length - 1]
  )
}

/** Share of cream laid over the satellite tiles in 2D so the plan reads clearly. */
export const IMAGERY_WASH = 0.12

const storageKey = (user: string) => `castor.basemap.${user}`

/** The basemap this user last chose, or the default. A view preference: it is not part of any layout. */
export function loadBasemap(user: string | null): Basemap {
  if (user === null) return DEFAULT_BASEMAP
  try {
    const stored = localStorage.getItem(storageKey(user))
    return isBasemap(stored) ? stored : DEFAULT_BASEMAP
  } catch {
    return DEFAULT_BASEMAP
  }
}

export function saveBasemap(user: string | null, basemap: Basemap): void {
  if (user === null) return
  try {
    localStorage.setItem(storageKey(user), basemap)
  } catch {
    // The preference is a convenience; a full or blocked storage only means it is not remembered
  }
}
