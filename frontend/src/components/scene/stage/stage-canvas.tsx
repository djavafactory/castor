import { Canvas } from '@react-three/fiber'
import type { ReactNode } from 'react'
import { ACESFilmicToneMapping } from 'three'
import { CAMERA, PRESETS } from './camera-presets'
import { Backdrop } from './lighting'

/** Pixel ratio range: sharper on desktop, 1.5 at most on small or touch screens. */
export function pixelRatioRange(): [number, number] {
  const small =
    typeof matchMedia === 'function' &&
    (matchMedia('(pointer: coarse)').matches || matchMedia('(max-width: 768px)').matches)
  return small ? [1, 1.5] : [1, 1.75]
}

/**
 * The 3D canvas of the port: antialias, logarithmic depth (the site is 9.5 km wide and 3 m of near plane), ACES
 * filmic tone mapping, the paper background with distance fog, a 30 degree lens, and a frame loop that renders on
 * demand (water and camera motion ask for frames; a still scene costs nothing).
 */
export function StageCanvas({
  children,
  onPointerMissed,
}: {
  children: ReactNode
  onPointerMissed?: () => void
}) {
  const start = PRESETS[0]
  return (
    <Canvas
      frameloop="demand"
      dpr={pixelRatioRange()}
      shadows="percentage"
      gl={{ antialias: true, logarithmicDepthBuffer: true }}
      camera={{ position: start.position, fov: CAMERA.fov, near: CAMERA.near, far: CAMERA.far }}
      onCreated={({ gl }) => {
        gl.toneMapping = ACESFilmicToneMapping
      }}
      onPointerMissed={onPointerMissed}
    >
      <Backdrop />
      {children}
    </Canvas>
  )
}
