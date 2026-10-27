import { useMapStore } from '@/components/map/map-store'
import type { LocalBox } from '@/components/map/map-view'
import { camera } from '@/components/scene/stage/view-state'
import type { SearchResult } from '@/lib/api'
import { fromLonLat, lonLatToScene, toLonLat, type Local } from '@/lib/geo'
import { useSelectionStore } from '@/lib/stores/selection-store'

/** How far the 3D camera keeps from a place that is only a point, metres. */
const PLACE_RADIUS_M = 90
/** Air around a shape the 3D camera frames, metres. */
const SHAPE_MARGIN_M = 15

function focus3d(centre: Local, radius: number) {
  const [lon, lat] = toLonLat(centre[0], centre[1])
  const [x, , z] = lonLatToScene(lon, lat)
  camera.focusOn([x, 0, z], radius)
}

/** A place: centres the 2D map (at least 0.6 px/m), drops the orange pin and focuses the 3D camera. */
export function goToPlace(lon: number, lat: number) {
  const point = fromLonLat(lon, lat)
  const map = useMapStore.getState()
  map.goTo(point)
  map.dropPin(point)
  focus3d(point, PLACE_RADIUS_M)
}

/** The local box of a WGS84 box: all four corners are converted because the frame is turned. */
export function localBoxOf(bbox: readonly [number, number, number, number]): LocalBox {
  const corners = [
    fromLonLat(bbox[0], bbox[1]),
    fromLonLat(bbox[2], bbox[1]),
    fromLonLat(bbox[0], bbox[3]),
    fromLonLat(bbox[2], bbox[3]),
  ]
  const xs = corners.map((c) => c[0])
  const ys = corners.map((c) => c[1])
  return { x0: Math.min(...xs), x1: Math.max(...xs), y0: Math.min(...ys), y1: Math.max(...ys) }
}

/** Zooms the 2D map to a box and frames it in 3D. */
function frame(box: LocalBox) {
  useMapStore.getState().fit(box)
  const radius = Math.hypot(box.x1 - box.x0, box.y1 - box.y0) / 2 + SHAPE_MARGIN_M
  focus3d([(box.x0 + box.x1) / 2, (box.y0 + box.y1) / 2], radius)
}

/** A shape: selects it, zooms the 2D map to it and frames it in 3D. */
function goToShape(hit: SearchResult) {
  const selection = useSelectionStore.getState()
  if (hit.kind === 'ASSET') selection.select(hit.id)
  else selection.selectReference(hit.id)
  frame(localBoxOf(hit.bbox))
}

/** [lon, lat] pairs anywhere inside the nested coordinates of a GeoJSON geometry. */
function positions(coordinates: unknown, out: [number, number][] = []): [number, number][] {
  if (!Array.isArray(coordinates)) return out
  if (typeof coordinates[0] === 'number' && typeof coordinates[1] === 'number') {
    out.push([coordinates[0], coordinates[1]])
  } else {
    for (const part of coordinates) positions(part, out)
  }
  return out
}

/** An asset of the draft (an issue row): selects it and zooms to its shape in 2D and 3D. */
export function goToAsset(id: string, geometry: unknown) {
  useSelectionStore.getState().select(id)
  const points = positions((geometry as { coordinates?: unknown } | undefined)?.coordinates)
  if (points.length === 0) return
  const lons = points.map((p) => p[0])
  const lats = points.map((p) => p[1])
  frame(localBoxOf([Math.min(...lons), Math.min(...lats), Math.max(...lons), Math.max(...lats)]))
}

/** What choosing a result does: places are visited, shapes are selected. */
export function goToResult(hit: SearchResult) {
  if (hit.kind === 'ASSET' || hit.kind === 'REFERENCE') goToShape(hit)
  else goToPlace(hit.lon, hit.lat)
}
