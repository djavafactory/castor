import { useEffect, useMemo } from 'react'
import { hex } from '@/features/scene/palette'
import { buildFlatBatches, type FlatItem } from './flat-geometry'
import { LAYERS } from './layer-heights'

/**
 * Flat layers on the ground: one mesh, so one draw call, per layer and colour. The heights and polygon offsets
 * come from layer-heights.ts. They never move, so nothing here ever animates.
 */
export function FlatLayers({
  items,
  name,
  opacity = 1,
}: {
  items: readonly FlatItem[]
  name: string
  /** Below 1 the layer is see-through (selection and ghost pads). */
  opacity?: number
}) {
  const batches = useMemo(() => buildFlatBatches(items), [items])
  useEffect(() => () => batches.forEach((b) => b.geometry.dispose()), [batches])
  return (
    <group name={name}>
      {batches.map((b) => {
        const { offset } = LAYERS[b.layer]
        return (
          <mesh key={b.key} geometry={b.geometry} name={`flat:${b.key}`} receiveShadow>
            <meshStandardMaterial
              color={hex(b.colour)}
              roughness={1}
              metalness={0}
              polygonOffset
              polygonOffsetFactor={offset}
              polygonOffsetUnits={offset}
              transparent={opacity < 1}
              opacity={opacity}
              depthWrite={opacity >= 1}
            />
          </mesh>
        )
      })}
    </group>
  )
}
