import type { Local } from './frame'

const EPS = 1e-9

function cross(o: Local, a: Local, b: Local): number {
  return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
}

function withinBox(a: Local, b: Local, p: Local): boolean {
  return (
    Math.min(a[0], b[0]) - EPS <= p[0] &&
    p[0] <= Math.max(a[0], b[0]) + EPS &&
    Math.min(a[1], b[1]) - EPS <= p[1] &&
    p[1] <= Math.max(a[1], b[1]) + EPS
  )
}

/** True when segments a-b and c-d share at least one point (touching counts). */
export function segmentsIntersect(a: Local, b: Local, c: Local, d: Local): boolean {
  const d1 = cross(c, d, a)
  const d2 = cross(c, d, b)
  const d3 = cross(a, b, c)
  const d4 = cross(a, b, d)
  if (
    ((d1 > EPS && d2 < -EPS) || (d1 < -EPS && d2 > EPS)) &&
    ((d3 > EPS && d4 < -EPS) || (d3 < -EPS && d4 > EPS))
  ) {
    return true
  }
  return (
    (Math.abs(d1) <= EPS && withinBox(c, d, a)) ||
    (Math.abs(d2) <= EPS && withinBox(c, d, b)) ||
    (Math.abs(d3) <= EPS && withinBox(a, b, c)) ||
    (Math.abs(d4) <= EPS && withinBox(a, b, d))
  )
}

/**
 * First pair of edges [i, j] (i < j) of a closed ring (open ring input) that cross, or null. Edge k runs from
 * point k to point k + 1; the last edge closes the ring. Neighbouring edges share a vertex by definition, so
 * they only count when the second edge doubles back over the first.
 */
export function selfIntersection(points: readonly Local[]): [number, number] | null {
  const n = points.length
  if (n < 4) return null
  for (let i = 0; i < n; i += 1) {
    for (let j = i + 1; j < n; j += 1) {
      const a = points[i]!
      const b = points[(i + 1) % n]!
      const c = points[j]!
      const d = points[(j + 1) % n]!
      const forward = j === i + 1
      const wraps = i === 0 && j === n - 1
      if (forward || wraps) {
        // The edges meet at `shared`; `near` and `far` are their other ends.
        const shared = forward ? b : a
        const e1 = forward ? a : b
        const e2 = forward ? d : c
        if (
          Math.abs(cross(shared, e1, e2)) <= EPS &&
          (withinBox(shared, e1, e2) || withinBox(shared, e2, e1))
        ) {
          return [i, j]
        }
        continue
      }
      if (segmentsIntersect(a, b, c, d)) return [i, j]
    }
  }
  return null
}
