import { segmentsIntersect, signedArea, type Local } from '@/lib/geo'

/** Plane geometry in local metres for the placement rules: small, exact enough for centimetres. */

export function pointInPolygon(p: Local, ring: readonly Local[]): boolean {
  let inside = false
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i]!
    const [xj, yj] = ring[j]!
    if (yi > p[1] !== yj > p[1] && p[0] < ((xj - xi) * (p[1] - yi)) / (yj - yi) + xi)
      inside = !inside
  }
  return inside
}

/** Distance from a point to a segment. */
export function distanceToSegment(p: Local, a: Local, b: Local): number {
  const dx = b[0] - a[0]
  const dy = b[1] - a[1]
  const len2 = dx * dx + dy * dy
  const t =
    len2 === 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2))
  return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy))
}

export function distanceToPolyline(p: Local, line: readonly Local[]): number {
  let best = Infinity
  for (let i = 0; i + 1 < line.length; i++)
    best = Math.min(best, distanceToSegment(p, line[i]!, line[i + 1]!))
  return best
}

/** Inside the ring, or within `tolerance` of its boundary. */
export function coversPoint(ring: readonly Local[], p: Local, tolerance: number): boolean {
  return pointInPolygon(p, ring) || distanceToPolyline(p, [...ring, ring[0]!]) <= tolerance
}

/** A ring covers another when every vertex is covered and no edge crosses the boundary properly. */
export function ringCovers(
  outer: readonly Local[],
  inner: readonly Local[],
  tolerance: number,
): boolean {
  if (!inner.every((p) => coversPoint(outer, p, tolerance))) return false
  for (let i = 0; i < inner.length; i++) {
    const a = inner[i]!
    const b = inner[(i + 1) % inner.length]!
    for (let j = 0; j < outer.length; j++) {
      const c = outer[j]!
      const d = outer[(j + 1) % outer.length]!
      if (
        segmentsIntersect(a, b, c, d) &&
        !coversPoint(outer, [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2], tolerance)
      ) {
        return false
      }
    }
  }
  return true
}

/** Area of a ring clipped by a convex counter-clockwise polygon (Sutherland-Hodgman). */
export function clippedArea(subject: readonly Local[], convex: readonly Local[]): number {
  const clip = signedArea([...convex]) < 0 ? [...convex].reverse() : [...convex]
  let out: Local[] = [...subject]
  for (let i = 0; i < clip.length && out.length > 0; i++) {
    const a = clip[i]!
    const b = clip[(i + 1) % clip.length]!
    const side = (p: Local) => (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])
    const input = out
    out = []
    for (let k = 0; k < input.length; k++) {
      const cur = input[k]!
      const prev = input[(k + input.length - 1) % input.length]!
      const sc = side(cur)
      const sp = side(prev)
      if (sc >= 0) {
        if (sp < 0) out.push(cross(prev, cur, sp, sc))
        out.push(cur)
      } else if (sp >= 0) {
        out.push(cross(prev, cur, sp, sc))
      }
    }
  }
  return out.length < 3 ? 0 : Math.abs(signedArea(out))
}

function cross(p: Local, q: Local, sp: number, sc: number): Local {
  const t = sp / (sp - sc)
  return [p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1])]
}

/** Smallest distance between a ring and a polyline; 0 when they touch or the line starts inside the ring. */
export function ringToLineDistance(ring: readonly Local[], line: readonly Local[]): number {
  if (line.some((p) => pointInPolygon(p, ring))) return 0
  let best = Infinity
  for (let i = 0; i < ring.length; i++) {
    const a = ring[i]!
    const b = ring[(i + 1) % ring.length]!
    for (let j = 0; j + 1 < line.length; j++) {
      if (segmentsIntersect(a, b, line[j]!, line[j + 1]!)) return 0
      best = Math.min(
        best,
        distanceToSegment(a, line[j]!, line[j + 1]!),
        distanceToSegment(b, line[j]!, line[j + 1]!),
        distanceToSegment(line[j]!, a, b),
        distanceToSegment(line[j + 1]!, a, b),
      )
    }
  }
  return best
}

/** The point of a polyline nearest to p, with the chainage along the line and the unit direction there. */
export function nearestOnPolyline(
  p: Local,
  line: readonly Local[],
): { point: Local; chainage: number; direction: Local } | null {
  let best: { point: Local; chainage: number; direction: Local; d: number } | null = null
  let run = 0
  for (let i = 0; i + 1 < line.length; i++) {
    const a = line[i]!
    const b = line[i + 1]!
    const dx = b[0] - a[0]
    const dy = b[1] - a[1]
    const len = Math.hypot(dx, dy)
    if (len === 0) continue
    const t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (len * len)))
    const point: Local = [a[0] + t * dx, a[1] + t * dy]
    const d = Math.hypot(p[0] - point[0], p[1] - point[1])
    if (!best || d < best.d)
      best = { point, chainage: run + t * len, direction: [dx / len, dy / len], d }
    run += len
  }
  return best && { point: best.point, chainage: best.chainage, direction: best.direction }
}

/** The sub-line from chainage `from` to `to` (clamped), as local points. */
export function sliceByChainage(line: readonly Local[], from: number, to: number): Local[] {
  const out: Local[] = []
  let run = 0
  for (let i = 0; i + 1 < line.length; i++) {
    const a = line[i]!
    const b = line[i + 1]!
    const len = Math.hypot(b[0] - a[0], b[1] - a[1])
    const at = (s: number): Local => [
      a[0] + ((b[0] - a[0]) * (s - run)) / len,
      a[1] + ((b[1] - a[1]) * (s - run)) / len,
    ]
    if (len > 0 && to > run && from < run + len) {
      const s0 = Math.max(from, run)
      const s1 = Math.min(to, run + len)
      if (out.length === 0) out.push(at(s0))
      out.push(at(s1))
    }
    run += len
  }
  return out
}
