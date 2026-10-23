import type { Local } from './frame'

/**
 * Corners of a rectangle in local metres around its anchor (the centre), counter-clockwise, starting at the
 * (-length/2, -width/2) corner before rotating. `rotationDeg` is counter-clockwise from local +X.
 */
export function rectangleLocal(
  anchor: Local,
  rotationDeg: number,
  length: number,
  width: number,
): Local[] {
  const a = (rotationDeg * Math.PI) / 180
  const c = Math.cos(a)
  const s = Math.sin(a)
  const hl = length / 2
  const hw = width / 2
  const corners: Local[] = [
    [-hl, -hw],
    [hl, -hw],
    [hl, hw],
    [-hl, hw],
  ]
  return corners.map(([dx, dy]) => [anchor[0] + dx * c - dy * s, anchor[1] + dx * s + dy * c])
}

/** Points of a circle in local metres, counter-clockwise from +X. The ring is open (first point not repeated). */
export function circleLocal(anchor: Local, radius: number, segments = 48): Local[] {
  const points: Local[] = []
  for (let i = 0; i < segments; i += 1) {
    const t = (2 * Math.PI * i) / segments
    points.push([anchor[0] + radius * Math.cos(t), anchor[1] + radius * Math.sin(t)])
  }
  return points
}
