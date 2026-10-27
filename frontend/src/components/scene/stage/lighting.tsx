import { useFrame, useThree } from '@react-three/fiber'
import { useEffect, useMemo, useRef } from 'react'
import { DirectionalLight, Object3D, Vector3 } from 'three'
import { hex, palette } from '@/features/scene/palette'
import { view } from './view-state'

/** Where the sun stands relative to the camera target, metres. */
const SUN_OFFSET = new Vector3(600, 1200, 400).normalize()
const SHADOW_MAP = 2048
const SHADOW_MIN = 60
const SHADOW_MAX = 450

/** Half the width of the shadow frustum for a camera distance: soft shadows only near what is looked at. */
export function shadowExtent(distance: number): number {
  return Math.min(SHADOW_MAX, Math.max(SHADOW_MIN, distance * 0.45))
}

/** Hemisphere + sun. The shadow frustum is small and follows the camera target. */
export function Lighting() {
  const sun = useRef<DirectionalLight>(null)
  const targetObject = useMemo(() => new Object3D(), [])
  const invalidate = useThree((s) => s.invalidate)
  const last = useRef({ x: NaN, z: NaN, extent: NaN })

  useEffect(() => {
    const light = sun.current
    if (!light) return
    light.target = targetObject
    light.shadow.mapSize.set(SHADOW_MAP, SHADOW_MAP)
    light.shadow.bias = -0.0004
    light.shadow.normalBias = 0.6
  }, [targetObject])

  useFrame(() => {
    const light = sun.current
    if (!light) return
    const extent = shadowExtent(view.distance)
    // Snap to a texel-sized grid so shadows do not shimmer while the target glides
    const texel = (2 * extent) / SHADOW_MAP
    const tx = Math.round(view.target.x / texel) * texel
    const tz = Math.round(view.target.z / texel) * texel
    const l = last.current
    if (l.x === tx && l.z === tz && l.extent === extent) return
    last.current = { x: tx, z: tz, extent }
    targetObject.position.set(tx, 0, tz)
    targetObject.updateMatrixWorld()
    light.position.set(tx, 0, tz).addScaledVector(SUN_OFFSET, 1500)
    const cam = light.shadow.camera
    cam.left = -extent
    cam.right = extent
    cam.top = extent
    cam.bottom = -extent
    cam.near = 100
    cam.far = 3200
    cam.updateProjectionMatrix()
    invalidate()
  })

  return (
    <>
      <primitive object={targetObject} />
      <hemisphereLight
        args={[hex(palette.light.sky), hex(palette.light.ground), 1.15]}
        name="sky"
      />
      <directionalLight
        ref={sun}
        name="sun"
        color={hex(palette.light.sun)}
        intensity={2.1}
        castShadow
      />
    </>
  )
}

/** The canvas background, also the fog colour. */
export function Backdrop() {
  return (
    <>
      <color attach="background" args={[palette.canvas]} />
      <fog attach="fog" args={[palette.canvas, 1000, 6000]} />
    </>
  )
}
