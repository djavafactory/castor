import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent,
} from 'react'
import {
  BUILDINGS_LAYER,
  ROADS_LAYER,
  categoryMap,
  layerOf,
  layerShown,
  useLayerVisible,
} from '@/components/scene/layers'
import { palette } from '@/features/scene/palette'
import { PlaceSearch } from '@/features/search/place-search'
import { useAssetTypes, useDraftLayout, usePort } from '@/lib/api'
import { getEnv } from '@/lib/env'
import { toLonLat } from '@/lib/geo'
import { usePortFrame } from '@/lib/geo/use-port-frame'
import { GOOGLE_NOTE } from '@/lib/basemap'
import { useReferenceBuildings, useReferenceRoads } from '@/lib/reference-api'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { DrawReadout } from '@/features/design/draw/draw-readout'
import {
  VERTEX_SLOP_PX,
  roundLonLat,
  shapeOf,
  sketchStats,
  type DrawShape,
} from '@/features/design/draw/draw-rules'
import { useFinishDraw } from '@/features/design/draw/use-finish-draw'
import { PlacementPill } from '@/features/design/place/placement-pill'
import { usePlaceAsset, usePlacementStore } from '@/features/design/place/use-placement'
import { useEditorStore } from '@/lib/stores/editor-store'
import { drawMap, type Ghost2D, type Sketch } from './map-draw'
import { MapFurniture } from './map-furniture-view'
import { PIN_MS, useMapStore } from './map-store'
import {
  assetShapes,
  conventionalTerminalCentre,
  osmShapes,
  pick,
  type MapShape,
} from './map-scene'
import { MapTiles } from './map-tiles'
import { localToScreen, screenToLocal, wheelFactor } from './map-view'

/** A pointer that moves less than this between press and release is a click, not a drag. */
const CLICK_SLOP_PX = 4
/** How near a click must be to a line or an edge to hit it. */
const REACH_PX = 5
const MAX_DPR = 2

interface Drag {
  pointerId: number
  button: number
  x: number
  y: number
  startX: number
  startY: number
  moved: boolean
}

/**
 * The 2D plan of the port in the quay frame (local metres): the basemap, the OpenStreetMap outlines and the assets of
 * the draft, bottom to top, with a scale bar, a north arrow and the place search. Drag, wheel and arrow keys move it.
 */
export function Map2D() {
  const frameReady = usePortFrame()
  const port = usePort()
  const draft = useDraftLayout(undefined, true, { sync: true })
  const types = useAssetTypes()
  const buildings = useReferenceBuildings(frameReady)
  const roads = useReferenceRoads(frameReady)
  const showBuildings = useLayerVisible(BUILDINGS_LAYER)
  const showRoads = useLayerVisible(ROADS_LAYER)
  const layerVisibility = useUiStore((s) => s.layerVisibility)
  const isolatedLayer = useUiStore((s) => s.isolatedLayer)
  const basemap = useUiStore((s) => s.basemap)
  const selectedAssetId = useSelectionStore((s) => s.selectedAssetId)
  const selectedReferenceId = useSelectionStore((s) => s.selectedReferenceId)
  const placed = useMapStore((s) => s.placed)
  const pin = useMapStore((s) => s.pin)

  const container = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const drag = useRef<Drag | null>(null)
  const frame = useRef(0)
  const scene = useRef<{
    assets: readonly MapShape[]
    background: readonly MapShape[]
  }>({ assets: [], background: [] })
  const paint = useRef<() => void>(() => {})
  const schedule = useRef<() => void>(() => {})
  const [tiles] = useState(() => new MapTiles())
  const tool = useEditorStore((s) => s.tool)
  const sketch = useRef<Omit<Sketch, 'cursor'> | null>(null)
  const { finish, place, typeOf } = useFinishDraw()
  const placing = tool?.kind === 'place'
  const placeAsset = usePlaceAsset()
  const ghost = useRef<Ghost2D | null>(null)

  // What the draw tool is doing: the shape kind of its type, and the sketch drawn from its vertices
  const drawing = useMemo((): DrawShape | 'point' | null => {
    const type = tool?.kind === 'draw' ? typeOf(tool.typeCode) : undefined
    return type ? shapeOf(type) : null
  }, [tool, typeOf])
  const sketchBase = useMemo(() => {
    if (!frameReady || !tool || (drawing !== 'polygon' && drawing !== 'line')) return null
    const stats = sketchStats(drawing, tool.points)
    return {
      points: stats.local,
      polygon: drawing === 'polygon',
      crossing: stats.crossing !== null,
    }
  }, [frameReady, tool, drawing])

  const allAssets = useMemo(
    () => (frameReady && draft.data ? assetShapes(draft.data.assets, types.data, palette.pad) : []),
    [frameReady, draft.data, types.data],
  )
  // A hidden layer is not drawn and cannot be hit
  const assets = useMemo(() => {
    const categories = categoryMap(types.data)
    const state = { layerVisibility, isolatedLayer }
    return allAssets.filter((s) => {
      const key = layerOf(s.typeCode, categories.get(s.typeCode))
      return key === undefined || layerShown(state, key)
    })
  }, [allAssets, types.data, layerVisibility, isolatedLayer])
  const buildingShapes = useMemo(
    () => (frameReady && buildings.data ? osmShapes(buildings.data, palette.map.osmOutline) : []),
    [frameReady, buildings.data],
  )
  const roadShapes = useMemo(
    () => (frameReady && roads.data ? osmShapes(roads.data, palette.map.osmOutline) : []),
    [frameReady, roads.data],
  )
  const background = useMemo(
    () => [...(showBuildings ? buildingShapes : []), ...(showRoads ? roadShapes : [])],
    [showBuildings, showRoads, buildingShapes, roadShapes],
  )

  useEffect(() => {
    paint.current = () => {
      const ctx = canvas.current?.getContext('2d')
      if (!ctx) return
      const { view, size, cursor } = useMapStore.getState()
      const ui = useUiStore.getState()
      const selection = useSelectionStore.getState()
      drawMap({
        ctx,
        size,
        dpr: Math.min(MAX_DPR, window.devicePixelRatio || 1),
        view,
        basemap: ui.basemap,
        tileTemplate: getEnv().VITE_SATELLITE_TILE_URL,
        tiles: tiles.get,
        background: scene.current.background,
        assets: scene.current.assets,
        selectedAssetId: selection.selectedAssetId,
        selectedReferenceId: selection.selectedReferenceId,
        sketch: sketch.current && {
          ...sketch.current,
          cursor: cursor ? [cursor.x, cursor.y] : null,
        },
        ghost: ghost.current,
      })
    }
    schedule.current = () => {
      if (frame.current !== 0) return
      frame.current = requestAnimationFrame(() => {
        frame.current = 0
        paint.current()
      })
    }
    // A tile that arrives late paints the map again
    return tiles.listen(() => schedule.current())
  }, [tiles])

  // Anything that changes what is on the map paints it again
  useEffect(() => {
    scene.current = { assets, background }
    sketch.current = sketchBase
    schedule.current()
  }, [assets, background, basemap, selectedAssetId, selectedReferenceId, frameReady, sketchBase])

  // The view and the size move often; they never go through React
  useEffect(() => {
    const unsubscribe = useMapStore.subscribe(() => schedule.current())
    // the placement ghost follows the pointer, drawn on the next frame
    const unsubscribeGhost = usePlacementStore.subscribe((s) => {
      ghost.current = s.plan && { ring: s.plan.ring, line: s.plan.line, valid: s.problem === null }
      schedule.current()
    })
    return () => {
      unsubscribe()
      unsubscribeGhost()
      cancelAnimationFrame(frame.current)
      frame.current = 0
    }
  }, [])

  // The canvas follows its box, with one backing pixel per device pixel
  useEffect(() => {
    const box = container.current
    const el = canvas.current
    if (!box || !el) return
    const resize = () => {
      const { width, height } = box.getBoundingClientRect()
      if (width < 1 || height < 1) return
      const dpr = Math.min(MAX_DPR, window.devicePixelRatio || 1)
      el.width = Math.round(width * dpr)
      el.height = Math.round(height * dpr)
      useMapStore.getState().setSize({ width, height })
    }
    resize()
    const observer = new ResizeObserver(resize)
    observer.observe(box)
    return () => observer.disconnect()
  }, [])

  // The wheel needs a listener that may cancel the page scroll, which React's does not allow
  useEffect(() => {
    const el = canvas.current
    if (!el) return
    const onWheel = (event: WheelEvent) => {
      event.preventDefault()
      const rect = el.getBoundingClientRect()
      const lines = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? rect.height : 1
      useMapStore
        .getState()
        .zoomBy(wheelFactor(event.deltaY * lines), [
          event.clientX - rect.left,
          event.clientY - rect.top,
        ])
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [])

  // First view: the middle of the conventional terminal at 0.5 px/m
  useEffect(() => {
    if (placed || !frameReady || !draft.isSuccess) return
    useMapStore.getState().placeInitial(conventionalTerminalCentre(allAssets) ?? [0, 0])
  }, [placed, frameReady, draft.isSuccess, allAssets])

  // The pin stays four seconds
  useEffect(() => {
    if (!pin) return
    const timer = setTimeout(() => useMapStore.getState().clearPin(pin.id), PIN_MS)
    return () => clearTimeout(timer)
  }, [pin])

  const localAt = useCallback((event: { clientX: number; clientY: number }) => {
    const rect = canvas.current?.getBoundingClientRect()
    const { view, size } = useMapStore.getState()
    return {
      px: event.clientX - (rect?.left ?? 0),
      py: event.clientY - (rect?.top ?? 0),
      local: screenToLocal(
        view,
        size,
        event.clientX - (rect?.left ?? 0),
        event.clientY - (rect?.top ?? 0),
      ),
    }
  }, [])

  function onPointerDown(event: PointerEvent<HTMLCanvasElement>) {
    container.current?.focus({ preventScroll: true })
    // Middle button would start the browser's auto-scroll
    if (event.button === 1) event.preventDefault()
    event.currentTarget.setPointerCapture(event.pointerId)
    drag.current = {
      pointerId: event.pointerId,
      button: event.button,
      x: event.clientX,
      y: event.clientY,
      startX: event.clientX,
      startY: event.clientY,
      moved: false,
    }
  }

  function onPointerMove(event: PointerEvent<HTMLCanvasElement>) {
    const map = useMapStore.getState()
    const { local } = localAt(event)
    if (frameReady) {
      const [lon, lat] = toLonLat(local[0], local[1])
      map.setCursor({ lon, lat, x: local[0], y: local[1] })
    }
    const d = drag.current
    if (!d || d.pointerId !== event.pointerId) return
    if (
      !d.moved &&
      Math.hypot(event.clientX - d.startX, event.clientY - d.startY) > CLICK_SLOP_PX
    ) {
      d.moved = true
    }
    if (d.moved) map.panBy(event.clientX - d.x, event.clientY - d.y)
    d.x = event.clientX
    d.y = event.clientY
  }

  function onPointerUp(event: PointerEvent<HTMLCanvasElement>) {
    const d = drag.current
    drag.current = null
    if (!d || d.moved || d.button !== 0) return
    if (placing) {
      void placeAsset({ keepTool: event.shiftKey })
      return
    }
    if (drawing !== null) {
      addVertex(event)
      return
    }
    // A click: the shape under it is selected, empty space clears the selection
    const { local } = localAt(event)
    const { view } = useMapStore.getState()
    const hit = pick(assets, background, local[0], local[1], REACH_PX / view.k)
    const selection = useSelectionStore.getState()
    if (!hit) selection.clear()
    else if (hit.source === 'asset') selection.select(hit.id)
    else selection.selectReference(hit.id)
  }

  /** A click of the draw tool: a vertex (not within 4 px of the last one), or the whole asset of a point type. */
  function addVertex(event: PointerEvent<HTMLCanvasElement>) {
    const { px, py, local } = localAt(event)
    const at = roundLonLat(toLonLat(local[0], local[1]))
    if (drawing === 'point') {
      void place(at)
      return
    }
    const { view, size } = useMapStore.getState()
    const last = sketch.current?.points.at(-1)
    if (last) {
      const [lx, ly] = localToScreen(view, size, last[0], last[1])
      if (Math.hypot(px - lx, py - ly) < VERTEX_SLOP_PX) return
    }
    useEditorStore.getState().addPoint(at)
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Enter' && (drawing === 'polygon' || drawing === 'line')) {
      event.preventDefault()
      void finish()
      return
    }
    const map = useMapStore.getState()
    switch (event.key) {
      case 'ArrowLeft':
        map.panKey(-1, 0)
        break
      case 'ArrowRight':
        map.panKey(1, 0)
        break
      case 'ArrowUp':
        map.panKey(0, -1)
        break
      case 'ArrowDown':
        map.panKey(0, 1)
        break
      case '+':
      case '=':
        map.zoomIn()
        break
      case '-':
        map.zoomOut()
        break
      default:
        return
    }
    event.preventDefault()
  }

  return (
    <div
      ref={container}
      role="region"
      aria-label="2D map"
      tabIndex={0}
      data-testid="map-2d"
      className="relative h-full w-full overflow-hidden bg-canvas outline-none focus-visible:ring-2 focus-visible:ring-signal focus-visible:ring-inset"
      onKeyDown={onKeyDown}
    >
      <canvas
        ref={canvas}
        role="img"
        aria-label="Plan of the port in local metres, north arrow top right"
        className={`block h-full w-full touch-none ${drawing || placing ? 'cursor-crosshair' : ''}`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={() => (drag.current = null)}
        onDoubleClick={() => {
          if (drawing === 'polygon' || drawing === 'line') void finish()
        }}
        onPointerLeave={() => useMapStore.getState().setCursor(null)}
        onContextMenu={(event) => event.preventDefault()}
      />
      <div className="absolute top-3 left-3 z-overlay">
        <PlaceSearch />
      </div>
      <DrawReadout onFinish={() => void finish()} />
      <PlacementPill containerRef={container} />
      <MapFurniture
        frameRotDeg={port.data?.frameRotDeg ?? 0}
        basemap={basemap}
        note={GOOGLE_NOTE}
      />
    </div>
  )
}
