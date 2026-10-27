import type { ThreeEvent } from '@react-three/fiber'
import { useFrame, useThree } from '@react-three/fiber'
import { useEffect, useMemo, useRef } from 'react'
import {
  BoxGeometry,
  EdgesGeometry,
  PlaneGeometry,
  type Mesh,
  type MeshBasicMaterial,
  type Sprite,
  type Sphere,
} from 'three'
import type { LayoutFeature } from '@/lib/api'
import { containersEnabled } from '@/lib/features'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { ContainerTerminalLayer } from '../containers/container-terminal-layer'
import { CONTAINERS_LAYER, useLayerVisible } from '../layers'
import { AssetVisual, CAPTION_PX, labelTexture } from '../models/asset-visual'
import { buildLayoutMeshes } from '../models/layout-batch'
import { COLOUR, HOVER_LABEL } from '../models/model-spec'
import { buildPickGeometry } from './pick-geometry'
import { placeLayout, type PickBox } from './placement'

/** A click that moved more than this many pixels was a camera drag, not a pick. */
const DRAG_PX = 4
const SELECTION_COLOUR = 0x1f6feb
const SIGNAL_COLOUR = COLOUR.ORANGE
/** The hover glow breathes between these opacities on a 250 ms sine. */
const GLOW_MIN = 0.22
const GLOW_MAX = 0.42
const GLOW_PERIOD_S = 0.25
/** Labels keep this size on screen and fade out beyond this distance from the camera. */
const LABEL_SCREEN_SCALE: [number, number, number] = [0.24, 0.03, 1]
const LABEL_MAX_DISTANCE = 1600

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

  const meshes = useMemo(
    () => buildLayoutMeshes(placed.assets.map((a) => ({ ...a.asset, id: a.id }))),
    [placed],
  )
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
  const hoverCaption = useMemo(
    () => (hover ? meshes.captions.find((c) => c.assetId === hover) : undefined),
    [hover, meshes],
  )
  const hoverTexture = useMemo(
    () => (hoverCaption ? labelTexture(hoverCaption.text, CAPTION_PX, HOVER_LABEL) : null),
    [hoverCaption],
  )
  useEffect(() => () => hoverTexture?.dispose(), [hoverTexture])

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
          c.texture &&
          c.assetId !== hover && (
            <Caption key={i} name="caption" position={c.position} texture={c.texture} />
          ),
      )}
      {hoverCaption && hoverTexture && (
        <Caption name="hover-label" position={hoverCaption.position} texture={hoverTexture} />
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
      {hover && hover !== selected && (
        <HoverGlow boxes={pickable.filter((b) => b.assetId === hover)} />
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

/** A label that keeps its size on screen and drops out when the camera is far away (decluttering). */
function Caption({
  name,
  position,
  texture,
}: {
  name: string
  position: [number, number, number]
  texture: NonNullable<ReturnType<typeof labelTexture>>
}) {
  const ref = useRef<Sprite>(null)
  useFrame(({ camera }) => {
    const sprite = ref.current
    if (!sprite) return
    sprite.visible = camera.position.distanceTo(sprite.position) <= LABEL_MAX_DISTANCE
  })
  return (
    <sprite ref={ref} name={name} position={position} scale={LABEL_SCREEN_SCALE}>
      <spriteMaterial map={texture} depthTest={false} transparent sizeAttenuation={false} />
    </sprite>
  )
}

/** Pulsing signal pad on the ground plus three edge shells around the hovered asset. */
function HoverGlow({ boxes }: { boxes: readonly PickBox[] }) {
  const invalidate = useThree((s) => s.invalidate)
  const materials = useRef<MeshBasicMaterial[]>([])
  const parts = useMemo(
    () =>
      boxes.map((b) => {
        const pad = new PlaneGeometry(b.size.length, b.size.width)
        const box = new BoxGeometry(b.size.length, b.size.height, b.size.width)
        const edges = new EdgesGeometry(box)
        box.dispose()
        return { box: b, pad, edges }
      }),
    [boxes],
  )
  useEffect(
    () => () =>
      parts.forEach((p) => {
        p.pad.dispose()
        p.edges.dispose()
      }),
    [parts],
  )
  useFrame(({ clock }) => {
    const wave = (Math.sin((clock.elapsedTime * 2 * Math.PI) / GLOW_PERIOD_S) + 1) / 2
    for (const m of materials.current) if (m) m.opacity = GLOW_MIN + (GLOW_MAX - GLOW_MIN) * wave
    invalidate() // the stage renders on demand: keep it breathing while hovered
  })
  return (
    <group name="hover-glow">
      {parts.map(({ box, pad, edges }, i) => (
        <group key={i} position={box.position} rotation={[0, box.rotationY, 0]}>
          <mesh
            geometry={pad}
            raycast={() => null}
            rotation={[-Math.PI / 2, 0, 0]}
            position={[0, 0.3 - box.size.height / 2, 0]}
          >
            <meshBasicMaterial
              ref={(m) => {
                if (m) materials.current[i] = m
              }}
              color={SIGNAL_COLOUR}
              transparent
              opacity={GLOW_MAX}
              depthWrite={false}
            />
          </mesh>
          {[1, 1.02, 1.04].map((k) => (
            <lineSegments key={k} geometry={edges} scale={k} raycast={() => null}>
              <lineBasicMaterial color={SIGNAL_COLOUR} depthTest={false} />
            </lineSegments>
          ))}
        </group>
      ))}
    </group>
  )
}
