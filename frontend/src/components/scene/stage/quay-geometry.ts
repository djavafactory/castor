import { BufferAttribute, BufferGeometry, Color } from 'three'
import { hex, palette } from '@/features/scene/palette'
import type { Local } from '@/lib/geo'
import type { FlatItem } from './flat-geometry'
import { WATER_Y } from './layer-heights'
import type { StageExtent } from './stage-extent'

/** Water shallow within this distance of the quay, fading to deep. */
export const SHALLOW_M = 420
export const FOAM_WIDTH_M = 5
export const KERB_WIDTH_M = 0.8
export const KERB_HEIGHT_M = 0.25
/** Metres of water per wave texture repeat. */
export const WAVE_TILE_M = 160

/** The quay line points inside the padded land width (the first points of the water polygon). */
export function quayLine(extent: StageExtent): Local[] {
  return extent.waterPolygon.slice(0, extent.waterPolygon.length - 2)
}

function geometryOf(
  positions: number[],
  index: number[],
  extra?: { uv?: number[]; color?: number[] },
) {
  const g = new BufferGeometry()
  g.setAttribute('position', new BufferAttribute(new Float32Array(positions), 3))
  if (extra?.uv) g.setAttribute('uv', new BufferAttribute(new Float32Array(extra.uv), 2))
  if (extra?.color) g.setAttribute('color', new BufferAttribute(new Float32Array(extra.color), 3))
  g.setIndex(index)
  g.computeVertexNormals()
  g.computeBoundingSphere()
  return g
}

const smooth = (t: number) => t * t * (3 - 2 * t)

/** Distances from the quay of the water grid rows; denser near the quay where the colour changes. */
const ROWS = [0, 40, 100, 180, 260, 340, SHALLOW_M, 800, 1400, 2000]

/**
 * The water plane at y = -2 on the water side of the quay line: shallow colour near the quay fading to deep,
 * as vertex colours. UVs repeat the wave texture every WAVE_TILE_M metres.
 */
export function waterGeometry(extent: StageExtent): BufferGeometry {
  const line = quayLine(extent)
  const depth = extent.waterPolygon[extent.waterPolygon.length - 1][1] - extent.waterPolygon[0][1]
  const rows = ROWS.map((d) => Math.min(d, -depth))
  const shallow = new Color(hex(palette.water.shallow))
  const deep = new Color(hex(palette.water.deep))
  const positions: number[] = []
  const uv: number[] = []
  const color: number[] = []
  const mix = new Color()
  for (const [x, y] of line) {
    for (const d of rows) {
      positions.push(x, WATER_Y, -(y - d))
      uv.push(x / WAVE_TILE_M, (y - d) / WAVE_TILE_M)
      mix.copy(shallow).lerp(deep, smooth(Math.min(1, d / SHALLOW_M)))
      color.push(mix.r, mix.g, mix.b)
    }
  }
  const index: number[] = []
  const cols = line.length
  const n = rows.length
  for (let i = 0; i < cols - 1; i++) {
    for (let j = 0; j < n - 1; j++) {
      const a = i * n + j
      const b = (i + 1) * n + j
      // Up-facing: along +x then away from the quay (towards +z in scene)
      index.push(a, a + 1, b, b, a + 1, b + 1)
    }
  }
  return geometryOf(positions, index, { uv, color })
}

/** Foam: a 5 m strip on the water, along the quay edge. */
export function foamItem(extent: StageExtent): FlatItem {
  const points = quayLine(extent).map(([x, y]): Local => [x, y - FOAM_WIDTH_M / 2])
  return { kind: 'ribbon', layer: 'foam', colour: palette.water.foam, points, width: FOAM_WIDTH_M }
}

/** The quay wall from y = -2 to 0 along the quay line, facing the water. */
export function quayWallGeometry(extent: StageExtent): BufferGeometry {
  const line = quayLine(extent)
  const positions: number[] = []
  const index: number[] = []
  line.forEach(([x, y], i) => {
    positions.push(x, 0, -y, x, WATER_Y, -y)
    if (i > 0) {
      const t0 = (i - 1) * 2
      const b0 = t0 + 1
      const t1 = i * 2
      const b1 = t1 + 1
      // Normal towards the water (+z in scene)
      index.push(t0, b0, b1, t0, b1, t1)
    }
  })
  return geometryOf(positions, index)
}

/** The kerb along the quay line: a low step, KERB_WIDTH_M wide, on the land side. */
export function kerbGeometry(extent: StageExtent): BufferGeometry {
  const line = quayLine(extent)
  const positions: number[] = []
  const index: number[] = []
  line.forEach(([x, y], i) => {
    // front top edge, back top edge, front bottom edge
    positions.push(x, KERB_HEIGHT_M, -y, x, KERB_HEIGHT_M, -(y + KERB_WIDTH_M), x, 0, -y)
    if (i > 0) {
      const p = (i - 1) * 3
      const q = i * 3
      index.push(p, q, q + 1, p, q + 1, p + 1) // top, up
      index.push(p + 2, q, p, p + 2, q + 2, q) // front, towards the water
    }
  })
  return geometryOf(positions, index)
}
