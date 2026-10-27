import { PerspectiveCamera, Vector3 } from 'three'

/** Camera presets in scene metres (scene X = local x, Z = -local y). Keys 1-5 pick the first five. */
export interface CameraPreset {
  key: string
  label: string
  position: [number, number, number]
  target: [number, number, number]
}

export const PRESETS: readonly CameraPreset[] = [
  { key: 'overview', label: 'Overview', position: [620, 840, 1180], target: [0, 0, -150] },
  { key: 'top', label: 'Top', position: [0, 2300, -149], target: [0, 0, -150] },
  { key: 'yard', label: 'Yard', position: [110, 170, 130], target: [-130, 0, -140] },
  { key: 'quay', label: 'Quay', position: [-180, 100, 280], target: [-420, 8, 0] },
  // Seen from the water side
  { key: 'ship', label: 'Ship', position: [-780, 80, 200], target: [-480, 12, 0] },
]

export const DEFAULT_PRESET = 'overview'

export const CAMERA = {
  fov: 30,
  near: 3,
  far: 20_000,
  damping: 0.08,
  /** Never below the ground. */
  maxPolar: Math.PI * 0.49,
  minDistance: 12,
  maxDistance: 6000,
  zoomStep: 1.35,
  autoRotateSpeed: 1.2,
  flyMs: 600,
} as const

export function presetByKey(key: string): CameraPreset | undefined {
  return PRESETS.find((p) => p.key === key)
}

/** Keys 1-5 -> preset; anything else undefined. */
export function presetForKey(pressed: string): CameraPreset | undefined {
  return /^[1-5]$/.test(pressed) ? PRESETS[Number(pressed) - 1] : undefined
}

export interface View {
  position: [number, number, number]
  target: [number, number, number]
}

/** Distance and direction are kept, the length is clamped to the allowed range. */
export function clampDistance(position: Vector3, target: Vector3): Vector3 {
  const offset = position.clone().sub(target)
  const length = offset.length()
  if (length === 0) return target.clone().add(new Vector3(0, CAMERA.minDistance, 0))
  const clamped = Math.min(CAMERA.maxDistance, Math.max(CAMERA.minDistance, length))
  return target.clone().add(offset.multiplyScalar(clamped / length))
}

/** Zoom by a factor toward the target (factor < 1 zooms in), clamped to 12..6000 m. */
export function zoomed(position: Vector3, target: Vector3, factor: number): Vector3 {
  const offset = position.clone().sub(target).multiplyScalar(factor)
  return clampDistance(target.clone().add(offset), target)
}

/** Keeps a camera position above the ground (polar angle at most 0.49 pi). */
export function keepAboveGround(position: Vector3, target: Vector3): Vector3 {
  const offset = position.clone().sub(target)
  const length = offset.length()
  if (length === 0) return position.clone()
  const minY = length * Math.cos(CAMERA.maxPolar)
  if (offset.y >= minY) return position.clone()
  const horizontal = Math.hypot(offset.x, offset.z)
  const newHorizontal = Math.sqrt(Math.max(0, length * length - minY * minY))
  const k = horizontal > 1e-9 ? newHorizontal / horizontal : 0
  return new Vector3(target.x + offset.x * k, target.y + minY, target.z + offset.z * k)
}

/**
 * The view that fits a box on the ground (scene x0..x1, z0..z1) in the frustum, from the overview direction. The
 * distance never exceeds the 6,000 m of the controls, so a box wider than the lens covers there is centred and cut.
 */
export function fitBox(
  box: { x0: number; x1: number; z0: number; z1: number },
  camera: Pick<PerspectiveCamera, 'fov' | 'aspect'>,
  height = 0,
): View {
  const cx = (box.x0 + box.x1) / 2
  const cz = (box.z0 + box.z1) / 2
  const halfW = (box.x1 - box.x0) / 2
  const halfD = (box.z1 - box.z0) / 2
  const tanV = Math.tan((camera.fov * Math.PI) / 360)
  const tanH = tanV * (camera.aspect || 1)
  // A tilted view sees depth foreshortened; fit the width exactly and the depth at the tilt.
  const direction = new Vector3(0.32, 0.78, 0.54).normalize()
  const fitW = halfW / tanH
  const fitD = halfD / (tanV * Math.max(0.3, direction.y))
  const distance = Math.min(CAMERA.maxDistance, Math.max(CAMERA.minDistance, fitW, fitD) * 1.08)
  const target = new Vector3(cx, height, cz)
  const position = target.clone().add(direction.multiplyScalar(distance))
  return { position: position.toArray(), target: target.toArray() }
}

/** Frames an asset footprint (a circle around its box) about 2.4x its size from a three-quarter angle. */
export function frameFootprint(
  centre: [number, number, number],
  radius: number,
  camera: Pick<PerspectiveCamera, 'fov' | 'aspect'>,
): View {
  const tanV = Math.tan((camera.fov * Math.PI) / 360)
  const tanH = tanV * Math.min(1, camera.aspect || 1)
  const distance = Math.min(CAMERA.maxDistance, Math.max(CAMERA.minDistance, (radius * 1.4) / tanH))
  const target = new Vector3(...centre)
  const direction = new Vector3(0.45, 0.62, 0.64).normalize()
  return {
    position: target.clone().add(direction.multiplyScalar(distance)).toArray(),
    target: target.toArray(),
  }
}
