import type { ThreeEvent } from '@react-three/fiber'
import { useMapStore } from '@/components/map/map-store'
import { toLonLat } from '@/lib/geo'
import { useEditorStore } from '@/lib/stores/editor-store'

/** Wider than the whole site (9.5 km), so the pointer always meets it. */
const REACH_M = 40_000

/**
 * While a tool is active the pointer position on the 3D ground feeds the same cursor readout as the 2D map. The plane
 * is not drawn (no colour, no depth); it only receives the pointer. It is unmounted when no tool is active.
 */
export function GroundCursor() {
  const active = useEditorStore((s) => s.tool !== null)
  if (!active) return null
  const report = (event: ThreeEvent<PointerEvent>) => {
    // Scene X is local x, scene Z is local -y
    const x = event.point.x
    const y = -event.point.z
    const [lon, lat] = toLonLat(x, y)
    useMapStore.getState().setCursor({ lon, lat, x, y })
  }
  return (
    <mesh
      name="ground-cursor"
      rotation={[-Math.PI / 2, 0, 0]}
      position={[0, 0.02, 0]}
      onPointerMove={report}
      onPointerOut={() => useMapStore.getState().setCursor(null)}
    >
      <planeGeometry args={[REACH_M, REACH_M]} />
      <meshBasicMaterial transparent opacity={0} depthWrite={false} colorWrite={false} />
    </mesh>
  )
}
