import { useFrame, useThree } from '@react-three/fiber'
import { useEffect, useRef } from 'react'
import type { Sphere } from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'

/** Orbit, pan and zoom with the mouse; when a bounding sphere arrives the camera frames it. */
export function OrbitRig({ focus }: { focus: Sphere | null }) {
  const camera = useThree((s) => s.camera)
  const dom = useThree((s) => s.gl.domElement)
  const invalidate = useThree((s) => s.invalidate)
  const controls = useRef<OrbitControls | null>(null)

  useEffect(() => {
    const c = new OrbitControls(camera, dom)
    c.enableDamping = true
    c.maxPolarAngle = Math.PI / 2 - 0.02 // never below the ground
    c.addEventListener('change', () => invalidate())
    controls.current = c
    return () => {
      c.dispose()
      controls.current = null
    }
  }, [camera, dom, invalidate])

  useEffect(() => {
    const c = controls.current
    if (!focus || !c) return
    const { center, radius } = focus
    camera.position.set(center.x, radius * 0.9, center.z + radius * 1.1)
    c.target.copy(center)
    c.minDistance = 20
    c.maxDistance = radius * 6
    c.update()
    invalidate()
  }, [focus, camera, invalidate])

  useFrame(() => controls.current?.update())
  return null
}
