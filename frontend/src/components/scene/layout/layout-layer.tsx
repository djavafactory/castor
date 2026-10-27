import type { ThreeEvent } from '@react-three/fiber'
import { useEffect, useMemo } from 'react'
import { BoxGeometry, EdgesGeometry, type Mesh, type Sphere } from 'three'
import type { LayoutFeature } from '@/lib/api'
import { containersEnabled } from '@/lib/features'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { ContainerTerminalLayer } from '../containers/container-terminal-layer'
import { CONTAINERS_LAYER, useLayerVisible } from '../layers'
import { AssetVisual, CAPTION_PX, CAPTION_SCALE, labelTexture } from '../models/asset-visual'
import { buildLayoutMeshes } from '../models/layout-batch'
import { buildPickGeometry } from './pick-geometry'
import { placeLayout, type PickBox } from './placement'

/** A click that moved more than this many pixels was a camera drag, not a pick. */
const DRAG_PX = 4
const SELECTION_COLOUR = 0x1f6feb

export interface LayoutHover {
  assetId: string
  x: number
  y: number
}

interface LayoutLayerProps {
  features: readonly LayoutFeature[]
  onHover?: (hover: LayoutHover | null) => void
  /** Receives the bounding sphere of the drawn layout (for framing the camera). */
  onBuilt?: (bounds: Sphere | null) => void
}

/**
 * The published layout, read-only: every asset through the model library, merged per material, plus the
 * container terminal. Clicking selects an asset; nothing here can move, resize or delete one.
 */
export function LayoutLayer({ features, onHover, onBuilt }: LayoutLayerProps) {
  const placed = useMemo(() => placeLayout(features), [features])
  const labels = useUiStore((s) => s.labels)
  const containersVisible = useLayerVisible(CONTAINERS_LAYER) && containersEnabled()
  const hover = useSelectionStore((s) => s.hoveredAssetId)
  const setHover = useSelectionStore((s) => s.hover)
  const select = useSelectionStore((s) => s.select)
  const selected = useSelectionStore((s) => s.selectedAssetId)

  const meshes = useMemo(() => buildLayoutMeshes(placed.assets.map((a) => a.asset)), [placed])
  useEffect(
    () => () => meshes.group.children.forEach((m) => (m as Mesh).geometry.dispose()),
    [meshes],
  )

  // Yards and cranes belong to the container layer: with it off they cannot be hit either
  const pickable = useMemo(() => {
    const hidden = containersVisible
      ? new Set<string>()
      : new Set([...placed.yards, ...placed.cranes].map((c) => c.id))
    return placed.picks.filter((b) => !hidden.has(b.assetId))
  }, [placed, containersVisible])
  const pick = useMemo(() => buildPickGeometry(pickable), [pickable])
  useEffect(() => () => pick?.geometry.dispose(), [pick])

  const captions = useMemo(
    () =>
      labels
        ? meshes.captions.map((c) => ({ ...c, texture: labelTexture(c.text, CAPTION_PX) }))
        : [],
    [labels, meshes],
  )
  useEffect(() => () => captions.forEach((c) => c.texture?.dispose()), [captions])

  useEffect(() => {
    if (!pick) return onBuilt?.(null)
    pick.geometry.computeBoundingSphere()
    onBuilt?.(pick.geometry.boundingSphere)
  }, [pick, onBuilt])

  // Switching the layer off removes the meshes without a pointer-out event
  useEffect(
    () => () => {
      setHover(null)
      onHover?.(null)
    },
    [setHover, onHover],
  )

  function hit(event: ThreeEvent<PointerEvent | MouseEvent>) {
    return event.faceIndex == null ? undefined : pick?.assetAtFace(event.faceIndex)
  }

  return (
    <group name="layout">
      <primitive object={meshes.group} />
      {captions.map(
        (c, i) =>
          c.texture && (
            <sprite key={i} name="caption" position={c.position} scale={CAPTION_SCALE}>
              <spriteMaterial map={c.texture} depthTest={false} transparent />
            </sprite>
          ),
      )}
      {placed.unknown.map(({ id, name, asset }) => (
        <group key={id} position={asset.position} rotation={[0, asset.rotationY ?? 0, 0]}>
          <AssetVisual modelKey={asset.modelKey} label={name} size={asset.size} />
        </group>
      ))}
      <ContainerTerminalLayer yards={placed.yards} cranes={placed.cranes} />
      {pick && (
        <mesh
          name="layout-pick"
          geometry={pick.geometry}
          onPointerMove={(event) => {
            const id = hit(event)
            if (!id) return
            event.stopPropagation()
            if (id !== hover) setHover(id)
            onHover?.({ assetId: id, x: event.nativeEvent.offsetX, y: event.nativeEvent.offsetY })
          }}
          onPointerOut={() => {
            setHover(null)
            onHover?.(null)
          }}
          onClick={(event) => {
            if (event.delta > DRAG_PX) return
            const id = hit(event)
            if (!id) return
            event.stopPropagation()
            select(id)
          }}
        >
          <meshBasicMaterial transparent opacity={0} depthWrite={false} colorWrite={false} />
        </mesh>
      )}
      {selected && <SelectionOutline boxes={pickable.filter((b) => b.assetId === selected)} />}
    </group>
  )
}

/** A thin frame around the selected asset. */
function SelectionOutline({ boxes }: { boxes: readonly PickBox[] }) {
  const edges = useMemo(
    () =>
      boxes.map((b) => {
        const box = new BoxGeometry(b.size.length, b.size.height, b.size.width)
        const geometry = new EdgesGeometry(box)
        box.dispose()
        return { box: b, geometry }
      }),
    [boxes],
  )
  useEffect(() => () => edges.forEach((e) => e.geometry.dispose()), [edges])
  return (
    <group name="selection-outline">
      {edges.map(({ box, geometry }, i) => (
        <lineSegments
          key={i}
          geometry={geometry}
          position={box.position}
          rotation={[0, box.rotationY, 0]}
        >
          <lineBasicMaterial color={SELECTION_COLOUR} depthTest={false} />
        </lineSegments>
      ))}
    </group>
  )
}
