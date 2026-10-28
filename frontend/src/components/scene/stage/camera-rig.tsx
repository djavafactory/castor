import { useFrame, useThree } from '@react-three/fiber'
import { useEffect, useRef } from 'react'
import { Vector3, type PerspectiveCamera } from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { useUiStore } from '@/lib/stores/ui-store'
import {
  CAMERA,
  fitBox,
  frameFootprint,
  keepAboveGround,
  presetByKey,
  clampDistance,
  zoomed,
  DEFAULT_PRESET,
  type View,
} from './camera-presets'
import { NEAR_DISTANCE_M } from './tiles'
import { prefersReducedMotion } from '@/lib/motion'
import { useCameraStore, view, type CameraCommand } from './view-state'

interface Fly {
  from: View
  to: View
  start: number
}

const easeInOut = (t: number) => t * t * (3 - 2 * t)

/** The land box of "Whole port" in scene metres. */
export interface FitBox {
  x0: number
  x1: number
  z0: number
  z1: number
}

/**
 * Orbit controls with damping, the presets and their 600 ms fly, zoom steps, auto-orbit and focusOn.
 * It also publishes the camera target and distance (view) for light, fog and imagery, and keeps the
 * on-demand frame loop alive only while something moves.
 */
export function CameraRig({ whole }: { whole: FitBox }) {
  const camera = useThree((s) => s.camera)
  const dom = useThree((s) => s.gl.domElement)
  const invalidate = useThree((s) => s.invalidate)
  const controls = useRef<OrbitControls | null>(null)
  const fly = useRef<Fly | null>(null)
  const done = useRef(0)
  const wholeRef = useRef(whole)
  const command = useCameraStore((s) => s.command)
  const autoRotate = useCameraStore((s) => s.autoRotate)
  const setNear = useCameraStore((s) => s.setNear)
  const setCameraPreset = useUiStore((s) => s.setCameraPreset)

  useEffect(() => {
    wholeRef.current = whole
  }, [whole])

  useEffect(() => {
    const c = new OrbitControls(camera, dom)
    c.enableDamping = true
    c.dampingFactor = CAMERA.damping
    c.maxPolarAngle = CAMERA.maxPolar
    c.minDistance = CAMERA.minDistance
    c.maxDistance = CAMERA.maxDistance
    c.autoRotateSpeed = CAMERA.autoRotateSpeed
    c.autoRotate = useCameraStore.getState().autoRotate
    c.addEventListener('change', () => invalidate())
    // The user took over: a running fly stops, and the preset is no longer the current view
    c.addEventListener('start', () => {
      fly.current = null
      setCameraPreset('custom')
    })
    const start = presetByKey(DEFAULT_PRESET)
    if (start) {
      camera.position.set(...start.position)
      c.target.set(...start.target)
      c.update()
    }
    controls.current = c
    invalidate()
    return () => {
      c.dispose()
      controls.current = null
    }
  }, [camera, dom, invalidate, setCameraPreset])

  useEffect(() => {
    if (controls.current) controls.current.autoRotate = autoRotate
    invalidate()
  }, [autoRotate, invalidate])

  useEffect(() => {
    const c = controls.current
    if (!command || command.id === done.current || !c) return
    done.current = command.id
    const to = resolve(command, camera as PerspectiveCamera, c.target, wholeRef.current)
    if (!to) return
    if (command.type === 'preset') setCameraPreset(command.key)
    else if (command.type !== 'zoom') setCameraPreset('custom')
    const from: View = { position: camera.position.toArray(), target: c.target.toArray() }
    if (prefersReducedMotion()) {
      apply(c, camera, to)
      invalidate()
    } else {
      fly.current = { from, to, start: performance.now() }
      invalidate()
    }
  }, [command, camera, invalidate, setCameraPreset])

  useFrame((state) => {
    const c = controls.current
    if (!c) return
    const f = fly.current
    if (f) {
      const t = Math.min(1, (performance.now() - f.start) / CAMERA.flyMs)
      const e = easeInOut(t)
      const position = new Vector3(...f.from.position).lerp(new Vector3(...f.to.position), e)
      const target = new Vector3(...f.from.target).lerp(new Vector3(...f.to.target), e)
      camera.position.copy(position)
      c.target.copy(target)
      if (t >= 1) fly.current = null
    }
    c.update()
    view.target.copy(c.target)
    view.distance = camera.position.distanceTo(c.target)
    const near = view.distance < NEAR_DISTANCE_M
    if (near !== useCameraStore.getState().near) setNear(near)
    const fog = state.scene.fog
    if (fog && 'near' in fog) {
      // The 9.5 km site never disappears in fog: fog is relative to the viewing distance
      fog.near = 1.5 * view.distance
      fog.far = 4 * view.distance
    }
    // Keep drawing only while the camera moves
    if (fly.current || c.autoRotate) invalidate()
  })

  return null
}

function apply(c: OrbitControls, camera: { position: Vector3 }, to: View) {
  camera.position.set(...to.position)
  c.target.set(...to.target)
  c.update()
}

/** The view a command leads to; always above ground and within 12..6000 m. */
export function resolve(
  command: CameraCommand,
  camera: { position: Vector3; fov: number; aspect: number },
  target: Vector3,
  whole: FitBox,
): View | null {
  let view: View | null = null
  switch (command.type) {
    case 'preset': {
      const p = presetByKey(command.key)
      view = p ? { position: p.position, target: p.target } : null
      break
    }
    case 'whole':
      view = fitBox(whole, camera)
      break
    case 'zoom':
      view = {
        position: zoomed(camera.position, target, command.factor).toArray(),
        target: target.toArray(),
      }
      break
    case 'focus':
      view = frameFootprint(command.centre, command.radius, camera)
      break
  }
  if (!view) return null
  const t = new Vector3(...view.target)
  const p = keepAboveGround(clampDistance(new Vector3(...view.position), t), t)
  return { position: p.toArray(), target: view.target }
}
