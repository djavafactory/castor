import { useMemo, useRef, useState } from 'react'
import { EmptyState, ErrorCard } from '@/components/states/states'
import { useAssetTypes, useCatalogue, useCurrentLayout, useDraftLayout } from '@/lib/api'
import { ApiError } from '@/lib/api-client'
import { usePort } from '@/lib/api'
import { getEnv } from '@/lib/env'
import { usePortFrame } from '@/lib/geo/use-port-frame'
import { useReferenceBuildings, useReferenceRoads } from '@/lib/reference-api'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { buildingHoverText, buildingTitle } from './buildings/building-text'
import { useUiStore } from '@/lib/stores/ui-store'
import { BuildingsLayer, type BuildingHover } from './buildings/buildings-layer'
import { CameraRig } from './stage/camera-rig'
import { footprintOf } from './stage/footprint'
import { Ground } from './stage/ground'
import { GroundCursor } from './stage/ground-cursor'
import { Lighting } from './stage/lighting'
import { OsmRoadsLayer } from './stage/osm-roads'
import { MonitorSelection } from './stage/selection'
import { landSceneBox, stageExtent } from './stage/stage-extent'
import { StageCanvas } from './stage/stage-canvas'
import { Water } from './stage/water'
import { draftFeatures } from './layout/draft-features'
import { EditGhost } from './layout/edit-ghost'
import { LayoutLayer, type LayoutHover } from './layout/layout-layer'
import { BUILDINGS_LAYER, useLayerVisible, visibleFeatures, ROADS_LAYER } from './layers'
import { hoverLines, humanKey } from './layout/asset-rows'
import { SceneTooltip } from './scene-tooltip'
import { EditGizmo3D } from '@/features/design/edit/edit-gizmo-3d'
import { EditReason } from '@/features/design/edit/edit-reason'
import { PlaceOnGround } from '@/features/design/place/place-on-ground'
import { PlacementPill } from '@/features/design/place/placement-pill'
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

/** The stage: flat satellite ground, sea and quay, then the reference layers; later replica tickets add theirs. */
/** The OSM reference layers and the published layout. Read-only: nothing in here edits an asset. */
function Scene() {
  const frameReady = usePortFrame()
  const port = usePort(getEnv().VITE_PORT_CODE)
  const buildings = useReferenceBuildings(frameReady)
  const roads = useReferenceRoads(frameReady)
  const showBuildings = useLayerVisible(BUILDINGS_LAYER)
  const showRoads = useLayerVisible(ROADS_LAYER)
  const imagery = useUiStore((s) => s.basemap === 'sentinel2')
  const clearSelection = useSelectionStore((s) => s.clear)
  const selectedRef = useSelectionStore((s) => s.selectedReferenceId)
  const layout = useCurrentLayout()
  const assetTypes = useAssetTypes()
  // Design shows the draft in 3D, Monitor the published layout
  const design = useUiStore((s) => s.mode === 'design')
  const draft = useDraftLayout(undefined, design)
  const draftAssets = draft.data?.assets
  const allFeatures = useMemo(
    () =>
      design && draftAssets ? draftFeatures(draftAssets) : layout.data?.data.features.features,
    [design, draftAssets, layout.data],
  )
  const layerVisibility = useUiStore((s) => s.layerVisibility)
  const isolatedLayer = useUiStore((s) => s.isolatedLayer)
  // Each asset sits on the layer of its type (asset_type.category); a hidden layer is not drawn or picked
  const features = useMemo(
    () =>
      allFeatures &&
      visibleFeatures(allFeatures, assetTypes.data, { layerVisibility, isolatedLayer }),
    [allFeatures, assetTypes.data, layerVisibility, isolatedLayer],
  )
  const typeColours = useMemo(
    () => new Map((assetTypes.data ?? []).map((t) => [t.code, t.colourHex])),
    [assetTypes.data],
  )
  const catalogue = useCatalogue()
  const cargoForms = useMemo(
    () => new Map((catalogue.data?.cargoTypes ?? []).map((c) => [c.code, c.storageForm])),
    [catalogue.data],
  )
  const host = useRef<HTMLDivElement>(null)
  const [hover, setHover] = useState<BuildingHover | null>(null)
  const [assetHover, setAssetHover] = useState<LayoutHover | null>(null)

  const extent = useMemo(
    () => (frameReady && port.data ? stageExtent(port.data) : null),
    [frameReady, port.data],
  )
  const whole = useMemo(
    () => (extent ? landSceneBox(extent) : { x0: -3500, x1: 6000, z0: -1500, z1: 0 }),
    [extent],
  )

  const hoveredAsset = assetHover && features?.find((f) => f.id === assetHover.assetId)

  const hovered =
    showBuildings && hover && buildings.data?.find((f) => f.properties.osmRef === hover.osmRef)
  const selected = useMemo(() => {
    const feature =
      showBuildings &&
      selectedRef &&
      buildings.data?.find((f) => f.properties.osmRef === selectedRef)
    return feature && extent ? footprintOf(feature) : null
  }, [showBuildings, selectedRef, buildings.data, extent])

  return (
    <div ref={host} className="relative h-full w-full" data-testid="port-scene">
      <StageCanvas onPointerMissed={clearSelection}>
        <Lighting />
        <CameraRig whole={whole} />
        {extent && (
          <>
            <Ground extent={extent} imagery={imagery} />
            <Water extent={extent} imagery={imagery} />
          </>
        )}
        {/* Unmounted, not hidden: a hidden mesh would still be hit by the raycaster */}
        {frameReady && roads.data && showRoads && <OsmRoadsLayer roads={roads.data} />}
        {frameReady && buildings.data && showBuildings && (
          <BuildingsLayer buildings={buildings.data} onHover={setHover} />
        )}
        {frameReady && features && (
          <LayoutLayer
            features={features}
            typeColours={typeColours}
            cargoForms={cargoForms}
            onHover={setAssetHover}
          />
        )}
        <EditGhost />
        {selected && <MonitorSelection ring={selected.ring} height={selected.height} />}
        {frameReady && <GroundCursor />}
        {frameReady && design && <PlaceOnGround />}
        {frameReady && design && <EditGizmo3D frameReady={frameReady} />}
      </StageCanvas>
      {design && <PlacementPill containerRef={host} />}
      {design && <EditReason view="3d" />}
      {layout.isError && (
        <div className="absolute top-3 right-3 z-menu w-72">
          <ErrorCard
            title="The layout could not be loaded"
            detail="The published layout is not available right now."
            reference={layout.error instanceof ApiError ? layout.error.reference : undefined}
            onRetry={() => void layout.refetch()}
          />
        </div>
      )}
      {buildings.isError && (
        <div className="absolute top-3 left-3 z-menu w-72">
          <ErrorCard
            title="Buildings could not be loaded"
            detail="The OpenStreetMap buildings are not available right now."
            reference={buildings.error instanceof ApiError ? buildings.error.reference : undefined}
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
