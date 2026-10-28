import { useMemo } from 'react'
import { DoubleSide, ExtrudeGeometry, Shape, ShapeGeometry } from 'three'
import { GHOST_INVALID, GHOST_VALID } from '@/components/map/map-draw'
import type { Local } from '@/lib/geo'
import { usePlacementStore } from './use-placement'

const VOLUME_OPACITY = 0.35
const PAD_OPACITY = 0.45
const PAD_LIFT_M = 0.05
/** A line asset (berth, conveyor) is shown as a strip this wide at least, so it can be seen from far. */
const MIN_STRIP_M = 3
const LINE_HEIGHT_M = 0.6

function shapeOf(ring: readonly Local[]): Shape {
  const shape = new Shape()
  ring.forEach(([x, y], i) => (i === 0 ? shape.moveTo(x, y) : shape.lineTo(x, y)))
  shape.closePath()
  return shape
}

/** The corners of a strip of the given width around a polyline, one quad per segment, as rings. */
export function stripRings(line: readonly Local[], width: number): Local[][] {
  const half = Math.max(width, MIN_STRIP_M) / 2
  const rings: Local[][] = []
  for (let i = 0; i + 1 < line.length; i++) {
    const [x0, y0] = line[i]!
    const [x1, y1] = line[i + 1]!
    const len = Math.hypot(x1 - x0, y1 - y0)
    if (len === 0) continue
    const nx = (-(y1 - y0) / len) * half
    const ny = ((x1 - x0) / len) * half
    rings.push([
      [x0 + nx, y0 + ny],
      [x1 + nx, y1 + ny],
      [x1 - nx, y1 - ny],
      [x0 - nx, y0 - ny],
    ])
  }
  return rings
}

/**
 * The placement ghost on the 3D ground: the footprint extruded to the real height at opacity 0.35 over a flat pad at
 * 0.45, green when the spot is valid and red when it is not. Drawn without depth write so it never hides the replica.
 */
export function PlacementGhost3D() {
  const plan = usePlacementStore((s) => s.plan)
  const valid = usePlacementStore((s) => s.problem === null)
  const parts = useMemo(() => {
    if (!plan) return null
    const rings = plan.ring ? [plan.ring] : stripRings(plan.line ?? [], plan.widthM)
    const depth = plan.ring ? Math.max(plan.heightM, 0.5) : LINE_HEIGHT_M
    return rings.map((ring) => ({
      pad: new ShapeGeometry(shapeOf(ring)),
      volume: new ExtrudeGeometry(shapeOf(ring), { depth, bevelEnabled: false }),
    }))
  }, [plan])
  if (!parts) return null
  const colour = valid ? GHOST_VALID : GHOST_INVALID
  return (
    <group
      name="placement-ghost"
      rotation={[-Math.PI / 2, 0, 0]}
      position={[0, PAD_LIFT_M, 0]}
      renderOrder={5}
    >
      {parts.map((p, i) => (
        <group key={i}>
          <mesh geometry={p.pad}>
            <meshBasicMaterial
              color={colour}
              transparent
              opacity={PAD_OPACITY}
              depthWrite={false}
              side={DoubleSide}
            />
          </mesh>
          <mesh geometry={p.volume}>
            <meshBasicMaterial
              color={colour}
              transparent
              opacity={VOLUME_OPACITY}
              depthWrite={false}
            />
          </mesh>
        </group>
      ))}
    </group>
  )
}
