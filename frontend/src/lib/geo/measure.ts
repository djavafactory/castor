import type { Local } from './frame'

/** Signed area in local m2; positive for a counter-clockwise ring. The ring is open (first point not repeated). */
export function signedArea(points: readonly Local[]): number {
  let sum = 0
  for (let i = 0; i < points.length; i += 1) {
    const [x1, y1] = points[i]!
    const [x2, y2] = points[(i + 1) % points.length]!
    sum += x1 * y2 - x2 * y1
  }
  return sum / 2
}

/** Area of a polygon in square metres of the local frame. */
export function polygonArea(points: readonly Local[]): number {
  return Math.abs(signedArea(points))
}

/** Length of an open polyline in local metres. */
export function lineLength(points: readonly Local[]): number {
  let total = 0
  for (let i = 1; i < points.length; i += 1) {
    total += Math.hypot(points[i]![0] - points[i - 1]![0], points[i]![1] - points[i - 1]![1])
  }
  return total
}

/** Perimeter of a closed polygon (open ring input) in local metres. */
export function polygonPerimeter(points: readonly Local[]): number {
  if (points.length < 2) return 0
  return lineLength([...points, points[0]!])
}
