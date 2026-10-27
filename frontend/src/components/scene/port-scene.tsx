import { Canvas } from '@react-three/fiber'
import { useCallback, useState } from 'react'
import type { Sphere } from 'three'
import { EmptyState, ErrorCard } from '@/components/states/states'
import { usePortFrame } from '@/lib/geo/use-port-frame'
import { useReferenceBuildings } from '@/lib/reference-api'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { buildingHoverText } from './buildings/building-text'
import { BuildingsLayer, type BuildingHover } from './buildings/buildings-layer'
import type { BuildingsGeometry } from './buildings/building-geometry'
import { BUILDINGS_LAYER, useLayerVisible } from './layers'
import { OrbitRig } from './orbit-rig'
import { webglAvailable } from './webgl'

/** The 3D replica of the port. Without WebGL it says so and loads nothing. */
export function PortScene() {
  const [supported] = useState(webglAvailable)
  if (!supported) {
    return (
      <EmptyState className="pt-12">
        3D view not available: this browser cannot draw 3D graphics (WebGL).
      </EmptyState>
    )
  }
  return <Scene />
}

/** Reference layers sit in here; later replica tickets add theirs. */
function Scene() {
  const frameReady = usePortFrame()
  const buildings = useReferenceBuildings(frameReady)
  const showBuildings = useLayerVisible(BUILDINGS_LAYER)
  const selectReference = useSelectionStore((s) => s.selectReference)
  const [focus, setFocus] = useState<Sphere | null>(null)
  const [hover, setHover] = useState<BuildingHover | null>(null)

  const onBuilt = useCallback(
    (built: BuildingsGeometry) => setFocus(built.geometry.boundingSphere),
    [],
  )

  const hovered =
    showBuildings && hover && buildings.data?.find((f) => f.properties.osmRef === hover.osmRef)

  return (
    <div className="relative h-full w-full" data-testid="port-scene">
      <Canvas
        frameloop="demand"
        camera={{ position: [0, 800, 800], near: 1, far: 40000, fov: 45 }}
        onPointerMissed={() => selectReference(null)}
      >
        <ambientLight intensity={1.1} />
        <directionalLight position={[600, 1200, 400]} intensity={2.2} />
        <OrbitRig focus={focus} />
        {/* Unmounted, not hidden: a hidden mesh would still be hit by the raycaster */}
        {frameReady && buildings.data && showBuildings && (
          <BuildingsLayer buildings={buildings.data} onHover={setHover} onBuilt={onBuilt} />
        )}
      </Canvas>
      {buildings.isError && (
        <div className="absolute top-3 left-3 z-menu w-72">
          <ErrorCard
            title="Buildings could not be loaded"
            detail="The OpenStreetMap buildings are not available right now."
            onRetry={() => void buildings.refetch()}
          />
        </div>
      )}
      {hovered && hover && (
        <p
          role="tooltip"
          className="pointer-events-none absolute z-popover rounded-lg border border-signal bg-paper px-2.5 py-1.5 text-xs text-ink shadow-popover"
          style={{ left: hover.x + 12, top: hover.y + 12 }}
        >
          {buildingHoverText(hovered.properties)}
        </p>
      )}
    </div>
  )
}
