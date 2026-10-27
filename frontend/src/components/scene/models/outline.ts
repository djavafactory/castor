import type { Pt } from './box-batch'
import type { ModelParams } from './model-spec'

/** Rectangle around the origin, counter-clockwise. */
export function rectRing(length: number, width: number): Pt[] {
  const l = length / 2
  const w = width / 2
  return [
    [-l, -w],
    [l, -w],
    [l, w],
    [-l, w],
  ]
}

/** The drawn footprint of an asset, or its size rectangle. */
export function ringOf(p: ModelParams): Pt[] {
  return p.footprint ?? rectRing(p.size.length, p.size.width)
}

/** The drawn path of a linear asset, or a straight line along its length. */
export function pathOf(p: ModelParams): Pt[] {
  return (
    p.path ?? [
      [-p.size.length / 2, 0],
      [p.size.length / 2, 0],
    ]
  )
}

/** Whether a point is inside a polygon (even-odd). */
export function insideRing(ring: Pt[], [x, z]: Pt): boolean {
  let inside = false
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, zi] = ring[i]!
    const [xj, zj] = ring[j]!
    if (zi > z !== zj > z && x < ((xj - xi) * (z - zi)) / (zj - zi) + xi) inside = !inside
  }
  return inside
}

/** Distance from the point to the nearest edge of a polygon. */
export function edgeDistance(ring: Pt[], [x, z]: Pt): number {
  let best = Infinity
  for (let i = 0; i < ring.length; i++) {
    const [ax, az] = ring[i]!
    const [bx, bz] = ring[(i + 1) % ring.length]!
    const dx = bx - ax
    const dz = bz - az
    const t = Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz || 1)))
    best = Math.min(best, Math.hypot(x - (ax + t * dx), z - (az + t * dz)))
  }
  return best
}

export function pathLength(points: Pt[]): number {
  let total = 0
  for (let i = 1; i < points.length; i++) {
    total += Math.hypot(points[i]![0] - points[i - 1]![0], points[i]![1] - points[i - 1]![1])
  }
  return total
}

/** Point and unit direction at a distance along a polyline (clamped to its ends). */
export function pointAlong(points: Pt[], distance: number): { at: Pt; dir: Pt } {
  let left = Math.max(0, distance)
  for (let i = 1; i < points.length; i++) {
    const [ax, az] = points[i - 1]!
    const [bx, bz] = points[i]!
    const length = Math.hypot(bx - ax, bz - az)
    if (left <= length || i === points.length - 1) {
      const t = length === 0 ? 0 : Math.min(1, left / length)
      return {
        at: [ax + (bx - ax) * t, az + (bz - az) * t],
        dir: length === 0 ? [1, 0] : [(bx - ax) / length, (bz - az) / length],
      }
    }
    left -= length
  }
  return { at: points[0] ?? [0, 0], dir: [1, 0] }
}

/** Distances at which things stand along a path: `first`, then every `step`, never past the end. */
export function stations(points: Pt[], step: number, first = 0): number[] {
  const total = pathLength(points)
  const out: number[] = []
  for (let d = first; d <= total + 1e-6; d += step) out.push(d)
  return out
}

/** A path moved sideways by `offset` (to its left), corners mitred. */
export function offsetPath(points: Pt[], offset: number): Pt[] {
  const normal = (a: Pt, b: Pt): Pt => {
    const length = Math.hypot(b[0] - a[0], b[1] - a[1]) || 1
    return [-(b[1] - a[1]) / length, (b[0] - a[0]) / length]
  }
  return points.map((p, i) => {
    const normals: Pt[] = []
    if (i > 0) normals.push(normal(points[i - 1]!, p))
    if (i < points.length - 1) normals.push(normal(p, points[i + 1]!))
    const nx = normals.reduce((s, n) => s + n[0], 0) / normals.length
    const nz = normals.reduce((s, n) => s + n[1], 0) / normals.length
    const norm = Math.hypot(nx, nz) || 1
    const miter = offset / Math.max(0.5, norm)
    return [p[0] + (nx / norm) * miter, p[1] + (nz / norm) * miter]
  })
}
