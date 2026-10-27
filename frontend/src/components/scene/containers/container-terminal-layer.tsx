import { containersEnabled } from '@/lib/features'
import { useLayerVisible, CONTAINERS_LAYER } from '../layers'
import { AssetVisual, type AssetVisualProps } from '../models/asset-visual'
import { ContainerYard } from './container-yard'
import type { YardSpec } from './container-stacks'

/** Where an asset stands: scene position (x east, y up, z south) and heading about Y, radians. */
interface Placement {
  position: [number, number, number]
  heading: number
}

export type PlacedYard = YardSpec & Placement & { id: string }
export type PlacedCrane = AssetVisualProps & Placement & { id: string }

/**
 * The whole container terminal: yards with their boxes, and the STS cranes and RTGs. One switch ("Containers")
 * shows or hides all of it, and with the feature off nothing is drawn. Unmounted, not hidden, so a hidden
 * mesh is never hit by the raycaster.
 */
export function ContainerTerminalLayer({
  yards,
  cranes,
}: {
  yards: readonly PlacedYard[]
  cranes: readonly PlacedCrane[]
}) {
  const visible = useLayerVisible(CONTAINERS_LAYER)
  if (!containersEnabled() || !visible) return null
  return (
    <group name="container-terminal">
      {yards.map(({ id, position, heading, ...yard }) => (
        <group key={id} position={position} rotation={[0, heading, 0]}>
          <ContainerYard {...yard} />
        </group>
      ))}
      {cranes.map(({ id, position, heading, ...crane }) => (
        <group key={id} position={position} rotation={[0, heading, 0]}>
          <AssetVisual {...crane} />
        </group>
      ))}
    </group>
  )
}
