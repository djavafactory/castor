/**
 * The only place positions are converted. Every 2D and 3D layer imports from here.
 * The frame (origin and rotation) comes from GET /api/v1/ports/{code} via setActiveFrame(); it is never
 * hard-coded. Formulas are SETUP-03 and are mirrored by the backend QuayFrame (shared fixtures prove it).
 */
import { createFrame, type Local, type LonLat, type QuayFrame, type QuayFrameParams } from './frame'
import { circleLocal, rectangleLocal } from './shapes'

export { createFrame, metresPerDegree } from './frame'
export type { Local, LonLat, QuayFrame, QuayFrameParams } from './frame'
export { geodesicDistance, trueBearing, vincentyInverse } from './geodesic'
export { segmentsIntersect, selfIntersection } from './intersect'
export { lineLength, polygonArea, polygonPerimeter, signedArea } from './measure'
export { circleLocal, rectangleLocal } from './shapes'

/** [x, height, z] of the 3D scene: scene X = local x, Y = up, Z = -local y. */
export type ScenePoint = [x: number, y: number, z: number]

let active: QuayFrame | null = null

/** Sets the frame every module-level conversion uses. Call it when the port record arrives. */
export function setActiveFrame(params: QuayFrameParams): QuayFrame {
  active = createFrame(params)
  return active
}

export function clearActiveFrame(): void {
  active = null
}

export function hasActiveFrame(): boolean {
  return active !== null
}

function frame(): QuayFrame {
  if (!active) throw new Error('The port frame is not loaded yet (GET /api/v1/ports/{code})')
  return active
}

export function toLonLat(x: number, y: number): LonLat {
  return frame().toLonLat(x, y)
}

export function fromLonLat(lon: number, lat: number): Local {
  return frame().fromLonLat(lon, lat)
}

export function headingToRotation(headingDeg: number): number {
  return frame().headingToRotation(headingDeg)
}

export function rotationToHeading(rot: number): number {
  return frame().rotationToHeading(rot)
}

/** WGS84 -> 3D scene position [x, height, -y]. */
export function lonLatToScene(lon: number, lat: number, height = 0): ScenePoint {
  const [x, y] = frame().fromLonLat(lon, lat)
  return [x, height, -y]
}

/** 3D scene position (x and z) -> WGS84 [lon, lat]. */
export function sceneToLonLat(sceneX: number, sceneZ: number): LonLat {
  return frame().toLonLat(sceneX, -sceneZ)
}

/**
 * Four corners of a rectangle as WGS84 [lon, lat], built in local metres. `anchor` is the centre,
 * `headingDeg` the true heading of the length axis (clockwise from north).
 */
export function rectangle(
  anchor: LonLat,
  headingDeg: number,
  length: number,
  width: number,
): LonLat[] {
  const f = frame()
  return rectangleLocal(
    f.fromLonLat(anchor[0], anchor[1]),
    f.headingToRotation(headingDeg),
    length,
    width,
  ).map(([x, y]) => f.toLonLat(x, y))
}

/** Points of a circle as WGS84 [lon, lat]; the ring is open. */
export function circle(anchor: LonLat, radius: number, segments = 48): LonLat[] {
  const f = frame()
  return circleLocal(f.fromLonLat(anchor[0], anchor[1]), radius, segments).map(([x, y]) =>
    f.toLonLat(x, y),
  )
}
