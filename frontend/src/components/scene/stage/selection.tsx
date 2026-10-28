import { useEffect, useMemo } from 'react'
import { BoxGeometry, BufferGeometry, EdgesGeometry, Float32BufferAttribute } from 'three'
import { hex, palette } from '@/features/scene/palette'
import type { Local } from '@/lib/geo'
import type { FlatItem } from './flat-geometry'
import { FlatLayers } from './flat-layers'

/** Monitor selection: a translucent signal footprint pad and three fading edge shells around the solid. */
const SHELL_SCALE = [1, 1.012, 1.026]
const SHELL_OPACITY = [0.95, 0.55, 0.28]
const PAD_OPACITY = 0.35
/** Design selection: a translucent blue box with darker edges. */
const DESIGN_OPACITY = 0.22

function openRing(ring: readonly Local[]): Local[] {
  const n = ring.length
  const closed = n > 1 && ring[0][0] === ring[n - 1][0] && ring[0][1] === ring[n - 1][1]
  return closed ? ring.slice(0, -1) : [...ring]
}

/** Edges of the prism over a footprint ring (bottom loop, top loop, vertical edges) in scene metres, grown by scale. */
export function prismEdges(ring: readonly Local[], height: number, scale = 1): BufferGeometry {
  const pts = openRing(ring)
  const cx = pts.reduce((s, p) => s + p[0], 0) / pts.length
  const cy = pts.reduce((s, p) => s + p[1], 0) / pts.length
  const at = (p: Local, y: number): number[] => [
    cx + (p[0] - cx) * scale,
    y * scale,
    -(cy + (p[1] - cy) * scale),
  ]
  const v: number[] = []
  pts.forEach((p, i) => {
    const q = pts[(i + 1) % pts.length]
    v.push(
      ...at(p, 0),
      ...at(q, 0),
      ...at(p, height),
      ...at(q, height),
      ...at(p, 0),
      ...at(p, height),
    )
  })
  const g = new BufferGeometry()
  g.setAttribute('position', new Float32BufferAttribute(v, 3))
  return g
}

export function MonitorSelection({ ring, height }: { ring: Local[]; height: number }) {
  const shells = useMemo(() => SHELL_SCALE.map((k) => prismEdges(ring, height, k)), [ring, height])
  const pad = useMemo<FlatItem[]>(
    () => [{ kind: 'polygon', layer: 'hoverPad', colour: palette.signal, ring }],
    [ring],
  )
  useEffect(() => () => shells.forEach((g) => g.dispose()), [shells])
  return (
    <group name="selection-monitor">
      <FlatLayers items={pad} name="selection-pad" opacity={PAD_OPACITY} />
      {shells.map((g, i) => (
        <lineSegments key={i} geometry={g} renderOrder={5 + i}>
          <lineBasicMaterial
            color={hex(palette.select.shells[i])}
            transparent
            opacity={SHELL_OPACITY[i]}
            depthWrite={false}
          />
        </lineSegments>
      ))}
    </group>
  )
}

/** Design selection: the bounding box of the asset, translucent blue with edges; red while an edit is refused. */
export function DesignSelection({
  size,
  position,
  rotationY = 0,
  invalid = false,
}: {
  size: [number, number, number]
  position: [number, number, number]
  rotationY?: number
  invalid?: boolean
}) {
  const [length, height, width] = size
  const box = useMemo(() => new BoxGeometry(length, height, width), [length, height, width])
  const edges = useMemo(() => new EdgesGeometry(box), [box])
  useEffect(
    () => () => {
      box.dispose()
      edges.dispose()
    },
    [box, edges],
  )
  return (
    <group position={position} rotation={[0, rotationY, 0]} name="selection-design">
      <mesh geometry={box} renderOrder={5}>
        <meshBasicMaterial
          color={hex(invalid ? palette.brick : palette.select.designFill)}
          transparent
          opacity={DESIGN_OPACITY}
          depthWrite={false}
        />
      </mesh>
      <lineSegments geometry={edges} renderOrder={6}>
        <lineBasicMaterial color={hex(invalid ? palette.brick : palette.select.designEdge)} />
      </lineSegments>
    </group>
  )
}
