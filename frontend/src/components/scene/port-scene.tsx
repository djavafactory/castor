import { Canvas } from '@react-three/fiber'
import { useCallback, useState } from 'react'
import type { Sphere } from 'three'
import { EmptyState, ErrorCard } from '@/components/states/states'
import { useAssetTypes, useCurrentLayout } from '@/lib/api'
import { usePortFrame } from '@/lib/geo/use-port-frame'
import { useReferenceBuildings } from '@/lib/reference-api'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { buildingHoverText, buildingTitle } from './buildings/building-text'
import { BuildingsLayer, type BuildingHover } from './buildings/buildings-layer'
import type { BuildingsGeometry } from './buildings/building-geometry'
import { LayoutLayer, type LayoutHover } from './layout/layout-layer'
import { BUILDINGS_LAYER, useLayerVisible } from './layers'
import { OrbitRig } from './orbit-rig'
import { hoverLines, humanKey } from './layout/asset-rows'
import { SceneTooltip } from './scene-tooltip'
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

/** The OSM reference layers and the published layout. Read-only: nothing in here edits an asset. */
function Scene() {
  const frameReady = usePortFrame()
  const buildings = useReferenceBuildings(frameReady)
  const showBuildings = useLayerVisible(BUILDINGS_LAYER)
  const layout = useCurrentLayout()
  const assetTypes = useAssetTypes()
  const features = layout.data?.data.features.features
  const [buildingsFocus, setBuildingsFocus] = useState<Sphere | null>(null)
  const [layoutFocus, setLayoutFocus] = useState<Sphere | null>(null)
  const [hover, setHover] = useState<BuildingHover | null>(null)
  const [assetHover, setAssetHover] = useState<LayoutHover | null>(null)
  const clearSelection = useSelectionStore((s) => s.clear)

  const onBuilt = useCallback(
    (built: BuildingsGeometry) => setBuildingsFocus(built.geometry.boundingSphere),
    [],
  )
  // The camera frames the layout once it is there, the buildings until then
  const focus = layoutFocus ?? buildingsFocus
  const hoveredAsset = assetHover && features?.find((f) => f.id === assetHover.assetId)

  const hovered =
    showBuildings && hover && buildings.data?.find((f) => f.properties.osmRef === hover.osmRef)

  return (
    <div className="relative h-full w-full" data-testid="port-scene">
      <Canvas
        frameloop="demand"
        camera={{ position: [0, 800, 800], near: 1, far: 40000, fov: 45 }}
        onPointerMissed={clearSelection}
      >
        <ambientLight intensity={1.1} />
        <directionalLight position={[600, 1200, 400]} intensity={2.2} />
        <OrbitRig focus={focus} />
        {/* Unmounted, not hidden: a hidden mesh would still be hit by the raycaster */}
        {frameReady && buildings.data && showBuildings && (
          <BuildingsLayer buildings={buildings.data} onHover={setHover} onBuilt={onBuilt} />
        )}
        {frameReady && features && (
          <LayoutLayer features={features} onHover={setAssetHover} onBuilt={setLayoutFocus} />
        )}
      </Canvas>
      {layout.isError && (
        <div className="absolute top-3 right-3 z-menu w-72">
          <ErrorCard
            title="The layout could not be loaded"
            detail="The published layout is not available right now."
            onRetry={() => void layout.refetch()}
          />
        </div>
      )}
      {buildings.isError && (
        <div className="absolute top-3 left-3 z-menu w-72">
          <ErrorCard
            title="Buildings could not be loaded"
            detail="The OpenStreetMap buildings are not available right now."
            onRetry={() => void buildings.refetch()}
          />
        </div>
      )}
      {hoveredAsset && assetHover && (
        <SceneTooltip
          x={assetHover.x}
          y={assetHover.y}
          lines={hoverLines(
            hoveredAsset.properties,
            assetTypes.data?.find((t) => t.code === hoveredAsset.properties.type)?.label ??
              humanKey(hoveredAsset.properties.type.toLowerCase()),
            (hoveredAsset.geometry as { type?: string }).type,
          )}
        />
      )}
      {hovered && hover && (
        <SceneTooltip
          x={hover.x}
          y={hover.y}
          lines={[
            buildingTitle(hovered.properties),
            ...buildingHoverText(hovered.properties).split(', ').slice(1),
          ]}
        />
      )}
    </div>
  )
}
