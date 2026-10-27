import { useFrame, useThree } from '@react-three/fiber'
import { useEffect, useMemo, useRef } from 'react'
import { CanvasTexture, RepeatWrapping, SRGBColorSpace, type MeshBasicMaterial } from 'three'
import { hex, palette } from '@/features/scene/palette'
import { prefersReducedMotion } from './camera-rig'
import { FlatLayers } from './flat-layers'
import { WATER_Y, WAVES_Y } from './layer-heights'
import { foamItem, kerbGeometry, quayWallGeometry, waterGeometry } from './quay-geometry'
import type { StageExtent } from './stage-extent'

/** Water drawn over satellite imagery is this see-through. */
export const IMAGERY_WATER_OPACITY = 0.35
const WAVE_SPEED = 0.006

/** A tile of soft short strokes; repeated over the water and slid slowly. */
export function waveTexture(): CanvasTexture | null {
  if (typeof document === 'undefined') return null
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = 256
  const ctx = canvas.getContext('2d')
  if (!ctx) return null
  ctx.strokeStyle = palette.water.foam
  ctx.lineCap = 'round'
  ctx.lineWidth = 2
  let seed = 7
  const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647
  for (let i = 0; i < 26; i++) {
    const x = rnd() * 256
    const y = rnd() * 256
    const w = 14 + rnd() * 22
    ctx.globalAlpha = 0.35 + rnd() * 0.45
    ctx.beginPath()
    ctx.moveTo(x - w / 2, y)
    ctx.quadraticCurveTo(x, y - 3, x + w / 2, y)
    ctx.stroke()
    // wrap so the strokes tile
    for (const dx of [-256, 256]) {
      ctx.beginPath()
      ctx.moveTo(x - w / 2 + dx, y)
      ctx.quadraticCurveTo(x + dx, y - 3, x + w / 2 + dx, y)
      ctx.stroke()
    }
  }
  const texture = new CanvasTexture(canvas)
  texture.wrapS = texture.wrapT = RepeatWrapping
  texture.colorSpace = SRGBColorSpace
  return texture
}

/**
 * The sea at y = -2 with foam, and the quay wall and kerb. Water is the only thing that animates (a wave stroke
 * texture slides; off with reduced motion). With satellite imagery on, the water is 35 % see-through over the image.
 */
export function Water({ extent, imagery }: { extent: StageExtent; imagery: boolean }) {
  const geometry = useMemo(() => waterGeometry(extent), [extent])
  const wall = useMemo(() => quayWallGeometry(extent), [extent])
  const kerb = useMemo(() => kerbGeometry(extent), [extent])
  const foam = useMemo(() => [foamItem(extent)], [extent])
  const waves = useMemo(() => waveTexture(), [])
  const invalidate = useThree((s) => s.invalidate)
  const waveMaterial = useRef<MeshBasicMaterial>(null)
  const still = useMemo(() => prefersReducedMotion(), [])

  useEffect(
    () => () => {
      geometry.dispose()
      wall.dispose()
      kerb.dispose()
      waves?.dispose()
    },
    [geometry, wall, kerb, waves],
  )

  useFrame((_, delta) => {
    const map = waveMaterial.current?.map
    if (!map || still) return
    map.offset.x += delta * WAVE_SPEED
    map.offset.y += delta * WAVE_SPEED * 0.6
    invalidate()
  })

  return (
    <group name="water-side">
      <group name="water">
        <mesh geometry={geometry} name="water-surface" renderOrder={1}>
          <meshStandardMaterial
            vertexColors
            roughness={1}
            metalness={0}
            transparent={imagery}
            opacity={imagery ? IMAGERY_WATER_OPACITY : 1}
            depthWrite={!imagery}
          />
        </mesh>
        {waves && (
          <mesh
            geometry={geometry}
            position={[0, WAVES_Y - WATER_Y, 0]}
            name="water-waves"
            renderOrder={2}
          >
            <meshBasicMaterial
              ref={waveMaterial}
              map={waves}
              transparent
              opacity={imagery ? 0.22 : 0.5}
              depthWrite={false}
              toneMapped={false}
            />
          </mesh>
        )}
        <FlatLayers items={foam} name="foam" />
      </group>
      <mesh geometry={wall} name="quay-wall">
        <meshStandardMaterial color={hex(palette.quayWall)} roughness={1} metalness={0} />
      </mesh>
      <mesh geometry={kerb} name="quay-kerb">
        <meshStandardMaterial color={hex(palette.quay)} roughness={0.95} metalness={0} />
      </mesh>
    </group>
  )
}
