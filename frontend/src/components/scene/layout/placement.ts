import { headingToRotation, lonLatToScene } from '@/lib/geo'
import type { LayoutFeature } from '@/lib/api'
import type { PlacedCrane, PlacedYard } from '../containers/container-terminal-layer'
import type { Pt } from '../models/box-batch'
import type { ModelDetails, ModelSize } from '../models/model-spec'
import type { LayoutAsset } from '../models/layout-batch'
import { getModelSpec } from '../models/registry'

const DEG = Math.PI / 180

/** Types the container layer draws (behind its own switch and feature flag), never the generic layer. */
const YARD_TYPE = 'CONTAINER_YARD'
const CONTAINER_CRANE_TYPES = new Set(['STS_CRANE', 'RTG'])

/** The default tiers and fill of a yard that has no container details. */
const DEFAULT_TIERS = 4
const DEFAULT_FILL = 0.6

/** One box a click can hit: centre, turn about Y and size, scene metres. */
export interface PickBox {
  assetId: string
  position: [x: number, y: number, z: number]
  rotationY: number
  size: ModelSize
}

export interface PlacedLayout {
  /** Assets drawn by the model library, merged per material. */
  assets: { id: string; asset: LayoutAsset }[]
  /** Assets whose model key the library does not know: drawn one by one as neutral boxes. */
  unknown: { id: string; name: string; asset: LayoutAsset }[]
  yards: PlacedYard[]
  cranes: PlacedCrane[]
  picks: PickBox[]
}

/** Scene offset (x, z) -> model space of an asset turned by rotationY about Y. */
function toModel(dx: number, dz: number, rotationY: number): Pt {
  const c = Math.cos(rotationY)
  const s = Math.sin(rotationY)
  return [dx * c - dz * s, dx * s + dz * c]
}

/** Model space -> scene offset (x, z). */
function toSceneOffset(mx: number, mz: number, rotationY: number): [number, number] {
  const c = Math.cos(rotationY)
  const s = Math.sin(rotationY)
  return [mx * c + mz * s, -mx * s + mz * c]
}

function ringPoints(coordinates: unknown): [number, number][] {
  if (!Array.isArray(coordinates)) return []
  const ring = coordinates[0]
  if (!Array.isArray(ring)) return []
  const points = ring as [number, number][]
  const first = points[0]
  const last = points[points.length - 1]
  // GeoJSON rings repeat the first point at the end; the models take an open ring
  return first && last && first[0] === last[0] && first[1] === last[1]
    ? points.slice(0, -1)
    : points
}

function bounds(points: readonly Pt[]) {
  const xs = points.map((p) => p[0])
  const zs = points.map((p) => p[1])
  return {
    minX: Math.min(...xs),
    maxX: Math.max(...xs),
    minZ: Math.min(...zs),
    maxZ: Math.max(...zs),
  }
}

function details(feature: LayoutFeature): ModelDetails {
  const d = feature.properties.details
  const out: ModelDetails = { name: feature.properties.name }
  if (d.warehouse) {
    out.doors = d.warehouse.dockDoors
    if (d.warehouse.roofRiseM != null) out.roofRise = d.warehouse.roofRiseM
  }
  if (d.storagePad) {
    out.bays = d.storagePad.bays
    out.rows = d.storagePad.rows
  }
  if (d.storage?.designFill != null) out.designFill = d.storage.designFill
  if (d.berth?.depthM != null) out.depth = d.berth.depthM
  return out
}

/**
 * Places every feature of a published layout in the scene: position from the anchor through the port frame,
 * turn from the heading, size from the asset (then the footprint, then the model's default size) and the
 * drawn footprint or path relative to the anchor. Needs the port frame (usePortFrame) to be active.
 */
export function placeLayout(features: readonly LayoutFeature[]): PlacedLayout {
  const out: PlacedLayout = { assets: [], unknown: [], yards: [], cranes: [], picks: [] }

  for (const feature of features) {
    const p = feature.properties
    const spec = getModelSpec(p.type)
    const [ax, , az] = lonLatToScene(p.anchor[0], p.anchor[1])
    const rotationY = p.headingDeg == null ? 0 : headingToRotation(p.headingDeg) * DEG

    // The drawn shape, relative to the anchor and turned back into model space
    const geometry = feature.geometry
    const around = (lon: number, lat: number): Pt => {
      const [x, , z] = lonLatToScene(lon, lat)
      return toModel(x - ax, z - az, rotationY)
    }
    const footprint =
      geometry.type === 'Polygon'
        ? ringPoints(geometry.coordinates).map(([lon, lat]) => around(lon, lat))
        : undefined
    const path =
      geometry.type === 'LineString' && Array.isArray(geometry.coordinates)
        ? (geometry.coordinates as [number, number][]).map(([lon, lat]) => around(lon, lat))
        : undefined

    const box = footprint && footprint.length > 2 ? bounds(footprint) : null
    const diameter = p.radiusM == null ? null : p.radiusM * 2
    const size: ModelSize = {
      length: p.lengthM ?? diameter ?? (box ? box.maxX - box.minX : (spec?.size.length ?? 10)),
      width:
        p.widthM ??
        diameter ??
        p.lineWidthM ??
        (box ? box.maxZ - box.minZ : (spec?.size.width ?? 10)),
      height: p.heightM ?? spec?.size.height ?? 5,
    }
    const position: [number, number, number] = [ax, p.elevationM ?? 0, az]
    const id = feature.id

    // What a click can hit: one box per path segment, otherwise one box around the footprint or the size
    if (path && path.length > 1) {
      for (let i = 1; i < path.length; i++) {
        const [x0, z0] = path[i - 1]
        const [x1, z1] = path[i]
        const [dx, dz] = toSceneOffset((x0 + x1) / 2, (z0 + z1) / 2, rotationY)
        out.picks.push({
          assetId: id,
          position: [ax + dx, position[1] + size.height / 2, az + dz],
          rotationY: rotationY - Math.atan2(z1 - z0, x1 - x0),
          size: {
            length: Math.hypot(x1 - x0, z1 - z0),
            width: Math.max(size.width, 2),
            height: Math.max(size.height, 1),
          },
        })
      }
    } else {
      const [cx, cz] = box ? [(box.minX + box.maxX) / 2, (box.minZ + box.maxZ) / 2] : [0, 0]
      const [dx, dz] = toSceneOffset(cx, cz, rotationY)
      out.picks.push({
        assetId: id,
        position: [ax + dx, position[1] + Math.max(size.height, 0.5) / 2, az + dz],
        rotationY,
        size: { ...size, height: Math.max(size.height, 0.5) },
      })
    }

    if (p.type === YARD_TYPE) {
      const yard = p.details.containerYard
      out.yards.push({
        id,
        code: p.code,
        length: size.length,
        width: size.width,
        maxTiers: yard?.maxTiers ?? DEFAULT_TIERS,
        designFill: yard?.designFill ?? DEFAULT_FILL,
        position,
        heading: rotationY,
      })
      continue
    }
    if (CONTAINER_CRANE_TYPES.has(p.type)) {
      out.cranes.push({
        id,
        modelKey: p.type,
        label: p.name,
        size,
        positionMode: p.details.mobile?.positionMode,
        uncertaintyM: p.details.mobile?.uncertaintyM ?? undefined,
        position,
        heading: rotationY,
      })
      continue
    }

    const asset: LayoutAsset = {
      modelKey: p.type,
      size,
      footprint: spec?.parametric ? footprint : undefined,
      path: spec?.parametric ? path : undefined,
      details: details(feature),
      position,
      rotationY,
    }
    if (spec) out.assets.push({ id, asset })
    else out.unknown.push({ id, name: p.name, asset })
  }
  return out
}
