import { useEffect, useMemo, useState } from 'react'
import {
  CanvasTexture,
  type BufferGeometry,
  LinearFilter,
  LinearMipmapLinearFilter,
  SRGBColorSpace,
} from 'three'
import { hex, palette } from '@/features/scene/palette'
import { getEnv } from '@/lib/env'
import { fromLonLat, toLonLat } from '@/lib/geo'
import { useUiStore } from '@/lib/stores/ui-store'
import { FlatLayers } from './flat-layers'
import { buildFlatBatches, type FlatItem } from './flat-geometry'
import { cellGeometry } from './ground-geometry'
import { LAYERS, WATER_IMAGERY_Y } from './layer-heights'
import type { StageExtent } from './stage-extent'
import {
  FAR_ZOOM,
  NEAR_ZOOM,
  TileCache,
  loadTileImage,
  paintCell,
  planCells,
  tilesCovering,
  type Cell,
} from './tiles'
import { useCameraStore } from './view-state'

export interface ImageryCell {
  cell: Cell
  texture: CanvasTexture
}

const cache = new TileCache(loadTileImage)

/**
 * Sentinel-2 imagery of the site as canvas textures, composed from Web Mercator tiles. The far zoom is used until the
 * camera comes within 1,500 m of its target. The previous cells stay on screen until the new ones are painted.
 * Failure (no tile drawn) leaves the plain land colour and is reported through `onFailed` - never a dialog.
 */
export function useImagery(
  extent: StageExtent,
  enabled: boolean,
  onFailed: (failed: boolean) => void,
) {
  const near = useCameraStore((s) => s.near)
  const zoom = near ? NEAR_ZOOM : FAR_ZOOM
  const [cells, setCells] = useState<ImageryCell[]>([])

  useEffect(() => {
    if (!enabled) {
      onFailed(false)
      return
    }
    const abort = new AbortController()
    const area = {
      x0: extent.x0,
      x1: extent.x1,
      y0: Math.min(...extent.waterPolygon.map((p) => p[1])),
      y1: Math.max(...extent.landPolygon.map((p) => p[1])),
    }
    const plan = planCells(area, toLonLat(0, 0)[1], zoom)
    const template = getEnv().VITE_SATELLITE_TILE_URL
    void Promise.all(
      plan.map(async (cell) => {
        const canvas = document.createElement('canvas')
        const corners = [
          toLonLat(cell.x0, cell.y0),
          toLonLat(cell.x1, cell.y0),
          toLonLat(cell.x0, cell.y1),
          toLonLat(cell.x1, cell.y1),
        ]
        const drawn = await paintCell(canvas, cell, tilesCovering(corners, zoom), {
          template,
          background: palette.land,
          cache,
          toLocal: fromLonLat,
          signal: abort.signal,
        })
        return { cell, canvas, drawn }
      }),
    ).then((painted) => {
      if (abort.signal.aborted) return
      if (painted.every((p) => p.drawn === 0)) {
        onFailed(true)
        return
      }
      onFailed(false)
      setCells(
        painted.map(({ cell, canvas }) => {
          const texture = new CanvasTexture(canvas)
          texture.colorSpace = SRGBColorSpace
          texture.minFilter = LinearMipmapLinearFilter
          texture.magFilter = LinearFilter
          texture.anisotropy = 8
          return { cell, texture }
        }),
      )
    })
    return () => abort.abort()
  }, [extent, enabled, zoom, onFailed])

  // Textures of replaced cells are released
  useEffect(() => () => cells.forEach((c) => c.texture.dispose()), [cells])

  return enabled ? cells : []
}

/** The land plane (y = 0): padded land extent cut at the quay line. */
export function landItems(extent: StageExtent): FlatItem[] {
  return [{ kind: 'polygon', layer: 'land', colour: palette.land, ring: extent.landPolygon }]
}

/**
 * The ground is perfectly flat. Without imagery (switched off, still loading or failed) the land plane shows the
 * plain land colour at y = 0. With it, the imagery plane at y = -0.04 shows on land, and the same image lies under
 * the water at y = -2.04. There is no height map, no displacement and no waves on land.
 */
export function Ground({
  extent,
  imagery,
  onFailed,
}: {
  extent: StageExtent
  imagery: boolean
  onFailed?: (failed: boolean) => void
}) {
  const setImageryFailed = useUiStore((s) => s.setImageryFailed)
  const report = onFailed ?? setImageryFailed
  const cells = useImagery(extent, imagery, report)
  const land = useMemo(() => landItems(extent), [extent])
  const shown = imagery && cells.length > 0

  return (
    <group name="ground">
      {!shown && <FlatLayers items={land} name="land" />}
      {cells.map((c, i) => (
        <ImageryCellMesh
          key={`${c.cell.x0}:${c.cell.y0}:${i}:${c.texture.uuid}`}
          extent={extent}
          imagery={c}
        />
      ))}
      <ShadowCatcher extent={extent} />
    </group>
  )
}

function ImageryCellMesh({ extent, imagery }: { extent: StageExtent; imagery: ImageryCell }) {
  const land = useMemo(
    () => cellGeometry(extent.landPolygon, imagery.cell, LAYERS.satelliteGround.y),
    [extent, imagery.cell],
  )
  const water = useMemo(
    () => cellGeometry(extent.waterPolygon, imagery.cell, WATER_IMAGERY_Y),
    [extent, imagery.cell],
  )
  return (
    <group name="satellite">
      {land && <Plane geometry={land} map={imagery.texture} name="satellite-land" />}
      {water && <Plane geometry={water} map={imagery.texture} name="satellite-water" />}
    </group>
  )
}

function Plane({
  geometry,
  map,
  name,
}: {
  geometry: BufferGeometry
  map: CanvasTexture
  name: string
}) {
  useEffect(() => () => geometry.dispose(), [geometry])
  return (
    <mesh geometry={geometry} name={name}>
      {/* Unlit and untoned: the image keeps the colours of the satellite */}
      <meshBasicMaterial map={map} toneMapped={false} />
    </mesh>
  )
}

/** Receives the soft shadows of buildings on the ground (and on the imagery under it). Invisible otherwise. */
function ShadowCatcher({ extent }: { extent: StageExtent }) {
  const geometry = useMemo(() => {
    const [batch] = landBatch(extent)
    return batch
  }, [extent])
  useEffect(() => () => geometry?.dispose(), [geometry])
  if (!geometry) return null
  return (
    <mesh geometry={geometry} position={[0, 0.006, 0]} receiveShadow name="shadow-catcher">
      <shadowMaterial color={hex(palette.ink)} opacity={0.16} transparent depthWrite={false} />
    </mesh>
  )
}

function landBatch(extent: StageExtent): BufferGeometry[] {
  return buildFlatBatches(landItems(extent)).map((b) => b.geometry)
}
