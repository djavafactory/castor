import { useEditorStore } from '@/lib/stores/editor-store'
import { PlacementGhost3D } from './placement-ghost-3d'
import { usePlaceAsset, usePlacementStore } from './use-placement'

/** Wider than the whole site (9.5 km), so the click always meets it. */
const REACH_M = 40_000
/** A press that moved more than this many pixels was a camera drag, not a click. */
const CLICK_SLOP_PX = 6

/**
 * The 3D side of placing (DESIGNER-04): the ghost on the ground and the click that places it. The pointer position
 * itself is fed to the cursor by GroundCursor. Unmounted unless a place tool is active or a tile is being dragged.
 */
export function PlaceOnGround() {
  const placing = useEditorStore((s) => s.tool?.kind === 'place')
  const dragging = usePlacementStore((s) => s.dragging !== null)
  const placeAsset = usePlaceAsset()
  if (!placing && !dragging) return null
  return (
    <>
      <PlacementGhost3D />
      {placing && (
        <mesh
          name="place-on-ground"
          rotation={[-Math.PI / 2, 0, 0]}
          position={[0, 0.03, 0]}
          onClick={(event) => {
            if (event.delta > CLICK_SLOP_PX) return
            event.stopPropagation()
            void placeAsset({ keepTool: event.nativeEvent.shiftKey })
          }}
        >
          <planeGeometry args={[REACH_M, REACH_M]} />
          <meshBasicMaterial transparent opacity={0} depthWrite={false} colorWrite={false} />
        </mesh>
      )}
    </>
  )
}
