import { useMemo, useState } from 'react'
import { EmptyState, ErrorCard } from '@/components/states/states'
import { usePort } from '@/lib/api'
import { getEnv } from '@/lib/env'
import { usePortFrame } from '@/lib/geo/use-port-frame'
import { useReferenceBuildings, useReferenceRoads } from '@/lib/reference-api'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { buildingHoverText } from './buildings/building-text'
import { BuildingsLayer, type BuildingHover } from './buildings/buildings-layer'
import { BUILDINGS_LAYER, ROADS_LAYER, useLayerVisible } from './layers'
import { CameraControls } from './stage/camera-controls'
import { CameraRig } from './stage/camera-rig'
import { footprintOf } from './stage/footprint'
import { Ground } from './stage/ground'
import { Lighting } from './stage/lighting'
import { OsmRoadsLayer } from './stage/osm-roads'
import { MonitorSelection } from './stage/selection'
import { landSceneBox, stageExtent } from './stage/stage-extent'
import { StageCanvas } from './stage/stage-canvas'
import { Water } from './stage/water'
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
function Scene() {
  const frameReady = usePortFrame()
  const port = usePort(getEnv().VITE_PORT_CODE)
  const buildings = useReferenceBuildings(frameReady)
  const roads = useReferenceRoads(frameReady)
  const showBuildings = useLayerVisible(BUILDINGS_LAYER)
  const showRoads = useLayerVisible(ROADS_LAYER)
  const imagery = useUiStore((s) => s.basemap === 'sentinel2')
  const selectReference = useSelectionStore((s) => s.selectReference)
  const selectedRef = useSelectionStore((s) => s.selectedReferenceId)
  const [hover, setHover] = useState<BuildingHover | null>(null)

  const extent = useMemo(
    () => (frameReady && port.data ? stageExtent(port.data) : null),
    [frameReady, port.data],
  )
  const whole = useMemo(
    () => (extent ? landSceneBox(extent) : { x0: -3500, x1: 6000, z0: -1500, z1: 0 }),
    [extent],
  )

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
    <div className="relative h-full w-full" data-testid="port-scene">
      <StageCanvas onPointerMissed={() => selectReference(null)}>
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
        {selected && <MonitorSelection ring={selected.ring} height={selected.height} />}
      </StageCanvas>
      <CameraControls />
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
