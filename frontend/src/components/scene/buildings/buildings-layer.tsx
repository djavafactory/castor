import type { ThreeEvent } from '@react-three/fiber'
import { useEffect, useMemo } from 'react'
import type { ReferenceFeature } from '@/lib/reference-api'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { buildBuildingsGeometry, type BuildingsGeometry } from './building-geometry'

/** A click that moved more than this many pixels was a camera drag, not a pick. */
const DRAG_PX = 4

export interface BuildingHover {
  osmRef: string
  x: number
  y: number
}

interface BuildingsLayerProps {
  buildings: readonly ReferenceFeature[]
  /** Called while the pointer is over a building, with its screen position; null when it leaves. */
  onHover?: (hover: BuildingHover | null) => void
  /** Receives the built geometry (a hook for later tickets, such as framing an asset). */
  onBuilt?: (built: BuildingsGeometry) => void
}

/**
 * All OSM buildings as one mesh: one geometry, one material, one draw call. The face index of a hit
 * tells which building it was. Reference features are read-only: picking only selects and hovers.
 */
export function BuildingsLayer({ buildings, onHover, onBuilt }: BuildingsLayerProps) {
  const built = useMemo(() => buildBuildingsGeometry(buildings), [buildings])
  const hoverReference = useSelectionStore((s) => s.hoverReference)
  const selectReference = useSelectionStore((s) => s.selectReference)

  useEffect(() => {
    onBuilt?.(built)
    return () => built.geometry.dispose()
  }, [built, onBuilt])

  // Switching the layer off removes the mesh without a pointer-out event
  useEffect(
    () => () => {
      hoverReference(null)
      onHover?.(null)
    },
    [hoverReference, onHover],
  )

  function pick(event: ThreeEvent<PointerEvent | MouseEvent>) {
    return event.faceIndex == null ? undefined : built.featureAtFace(event.faceIndex)
  }

  return (
    <mesh
      name="buildings-osm"
      castShadow
      receiveShadow
      geometry={built.geometry}
      onPointerMove={(event) => {
        const hit = pick(event)
        if (!hit) return
        event.stopPropagation()
        hoverReference(hit.osmRef)
        onHover?.({
          osmRef: hit.osmRef,
          x: event.nativeEvent.offsetX,
          y: event.nativeEvent.offsetY,
        })
      }}
      onPointerOut={() => {
        hoverReference(null)
        onHover?.(null)
      }}
      onClick={(event) => {
        if (event.delta > DRAG_PX) return
        const hit = pick(event)
        if (!hit) return
        event.stopPropagation()
        selectReference(hit.osmRef)
      }}
    >
      <meshStandardMaterial vertexColors roughness={0.9} metalness={0} />
    </mesh>
  )
}
