import { palette } from '@/features/scene/palette'
import { IMAGERY_WASH, MIN_TILE_ZOOM, gridStepFor, tileZoomFor, type Basemap } from '@/lib/basemap'
import { fromLonLat, toLonLat } from '@/lib/geo'
import {
  TILE_PX,
  SEAM_OVERLAP_PX,
  tileCorner,
  tilesCovering,
  tileUrl,
  type TileId,
} from '../scene/stage/tiles'
import type { Local } from '@/lib/geo'
import { HANDLE_PX, KNOB_RADIUS_PX, type Handle } from '@/features/design/edit/edit-ops'
import { boxOf, boxesTouch, labelFits, type MapShape } from './map-scene'
import {
  localToScreen,
  tileMatrix,
  viewBox,
  type LocalBox,
  type MapSize,
  type MapView,
} from './map-view'

/** Hands out a tile image when it is ready, and starts loading it when it is not. */
export type TileSource = (url: string) => CanvasImageSource | null

export interface DrawInput {
  ctx: CanvasRenderingContext2D
  size: MapSize
  dpr: number
  view: MapView
  basemap: Basemap
  tileTemplate: string
  tiles: TileSource
  /** Background outlines (OpenStreetMap) and the assets, the latter already in draw order. */
  background: readonly MapShape[]
  assets: readonly MapShape[]
  selectedAssetId: string | null
  selectedReferenceId: string | null
  /** The outline being drawn (DESIGNER-05), if any. */
  sketch?: Sketch | null
  /** The placement ghost (DESIGNER-04): where the asset would land, green when the spot is valid. */
  ghost?: Ghost2D | null
  /** Assets with an error in the validation: outlined red. */
  errorIds?: ReadonlySet<string>
  /** The shape being dragged (DESIGNER-06): drawn instead of the stored one. */
  preview?: PreviewDraw | null
  /** Editing handles of the selected asset. */
  handles?: readonly Handle[]
  /** The line of the Measure tool, if any. */
  measure?: MeasureDraw | null
  /** Outlines in the accuracy colours, approximate ones dashed (the Accuracy checkbox). */
  accuracy?: boolean
}

/** The Measure line in local metres with its label. */
export interface MeasureDraw {
  from: Local
  to: Local
  label: string
}

/** The ghost of the placement tool in local metres. A footprint has a ring, a line asset a line. */
export interface Ghost2D {
  ring: readonly Local[] | null
  line: readonly Local[] | null
  valid: boolean
}

export const GHOST_VALID = palette.moss
export const GHOST_INVALID = palette.brick
export const GHOST_ALPHA = 0.45
export const GHOST_OUTLINE_PX = 2

export interface PreviewDraw {
  assetId: string
  rings: Local[][]
  closed: boolean
  valid: boolean
}

/** Outline widths in pixels (DESIGNER-06): the selection in signal orange, errors and refused edits in red. */
const OUTLINE_PX = 2.5
/** Accuracy outlines sit under the selection and error outlines. */
const ACCURACY_PX = 2

/** The outline in progress: vertices and the cursor in local metres. */
export interface Sketch {
  points: readonly Local[]
  cursor: Local | null
  polygon: boolean
  /** A polygon whose outline crosses itself is stroked red. */
  crossing: boolean
}

const VERTEX_PX = 8

const paths = new WeakMap<MapShape, Path2D>()

/** The shape as a path in local metres; built once, drawn through the view transform on every frame. */
function pathOf(shape: MapShape): Path2D {
  let path = paths.get(shape)
  if (!path) {
    path = new Path2D()
    for (const ring of shape.rings) {
      ring.forEach(([x, y], i) => (i === 0 ? path!.moveTo(x, y) : path!.lineTo(x, y)))
      if (shape.closed && ring.length > 0) path.closePath()
    }
    paths.set(shape, path)
  }
  return path
}

/** Sets the transform that turns local metres into device pixels (y up on screen). */
function localTransform(ctx: CanvasRenderingContext2D, view: MapView, size: MapSize, dpr: number) {
  const s = dpr * view.k
  ctx.setTransform(
    s,
    0,
    0,
    -s,
    dpr * (size.width / 2 - view.x * view.k),
    dpr * (size.height / 2 + view.y * view.k),
  )
}

function screenTransform(ctx: CanvasRenderingContext2D, dpr: number) {
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
}

/** Colour with an alpha, for hex colours of the palette and the asset types. */
export function withAlpha(colour: string, alpha: number): string {
  const n = parseInt(colour.slice(1), 16)
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`
}

function drawGrid(ctx: CanvasRenderingContext2D, view: MapView, size: MapSize, dpr: number) {
  const step = gridStepFor(view.k)
  const box = viewBox(view, size)
  localTransform(ctx, view, size, dpr)
  ctx.beginPath()
  for (let x = Math.ceil(box.x0 / step) * step; x <= box.x1; x += step) {
    ctx.moveTo(x, box.y0)
    ctx.lineTo(x, box.y1)
  }
  for (let y = Math.ceil(box.y0 / step) * step; y <= box.y1; y += step) {
    ctx.moveTo(box.x0, y)
    ctx.lineTo(box.x1, y)
  }
  ctx.strokeStyle = palette.map.grid
  ctx.lineWidth = 1 / view.k
  ctx.stroke()
}

/** The tiles of one zoom that touch the view. */
function tilesInView(view: MapView, size: MapSize, zoom: number): TileId[] {
  const box = viewBox(view, size)
  const corners = [
    toLonLat(box.x0, box.y0),
    toLonLat(box.x1, box.y0),
    toLonLat(box.x0, box.y1),
    toLonLat(box.x1, box.y1),
  ]
  return tilesCovering(corners, zoom)
}

/** Draws the tiles of one zoom; returns how many were not there yet. */
function drawTileSet(input: DrawInput, tiles: readonly TileId[]): number {
  const { ctx, view, size, dpr } = input
  let missing = 0
  for (const t of tiles) {
    const image = input.tiles(tileUrl(input.tileTemplate, t))
    if (!image) {
      missing++
      continue
    }
    const nw = fromLonLat(...tileCorner(t.x, t.y, t.z))
    const ne = fromLonLat(...tileCorner(t.x + 1, t.y, t.z))
    const sw = fromLonLat(...tileCorner(t.x, t.y + 1, t.z))
    ctx.setTransform(...tileMatrix(nw, ne, sw, view, size, dpr, TILE_PX))
    const o = SEAM_OVERLAP_PX
    ctx.drawImage(image, -o, -o, TILE_PX + 2 * o, TILE_PX + 2 * o)
  }
  return missing
}

/** Satellite tiles with the cream wash; while sharper tiles load, the coarser ones underneath stay on screen. */
function drawImagery(input: DrawInput) {
  const { ctx, view, size, dpr } = input
  const lat = toLonLat(view.x, view.y)[1]
  const zoom = tileZoomFor(lat, view.k)
  const wanted = tilesInView(view, size, zoom)
  // A wide view at a high zoom would be a flood of requests; the clamp of 8-14 keeps it small, this keeps it safe
  if (wanted.length > 120) return
  const sharp = wanted.map((t) => tileUrl(input.tileTemplate, t))
  const ready = sharp.every((url) => input.tiles(url) !== null)
  if (!ready && zoom > MIN_TILE_ZOOM) drawTileSet(input, tilesInView(view, size, zoom - 1))
  drawTileSet(input, wanted)
  screenTransform(ctx, dpr)
  ctx.fillStyle = withAlpha(palette.canvas, IMAGERY_WASH)
  ctx.fillRect(0, 0, size.width, size.height)
}

function strokeShape(
  ctx: CanvasRenderingContext2D,
  shape: MapShape,
  colour: string,
  px: number,
  k: number,
) {
  ctx.strokeStyle = colour
  ctx.lineWidth = px / k
  ctx.stroke(pathOf(shape))
}

function drawBackground(input: DrawInput, visible: LocalBox) {
  const { ctx, view, size, dpr } = input
  localTransform(ctx, view, size, dpr)
  ctx.lineJoin = 'round'
  for (const shape of input.background) {
    if (boxesTouch(shape.box, visible)) strokeShape(ctx, shape, palette.map.osmOutline, 1, view.k)
  }
}

function drawAsset(ctx: CanvasRenderingContext2D, shape: MapShape, k: number) {
  const path = pathOf(shape)
  if (shape.closed) {
    if (shape.typeCode === 'TERMINAL_BOUNDARY') {
      // A terminal is an outline around everything inside it
      ctx.setLineDash([10 / k, 6 / k])
      strokeShape(ctx, shape, shape.colour, 2, k)
      ctx.setLineDash([])
      return
    }
    ctx.fillStyle = withAlpha(shape.colour, 0.72)
    ctx.fill(path, 'evenodd')
    strokeShape(ctx, shape, shape.colour, 1, k)
    return
  }
  // A line is as wide as its width on the ground, and never thinner than a hairline on screen
  ctx.lineCap = 'butt'
  const px = Math.max(1.5, (shape.lineWidthM ?? 0) * k)
  strokeShape(ctx, shape, shape.colour, px, k)
}

function drawQuayLine(input: DrawInput, visible: LocalBox) {
  const { ctx, view, size, dpr } = input
  if (visible.y0 > 0 || visible.y1 < 0) return
  localTransform(ctx, view, size, dpr)
  ctx.beginPath()
  ctx.moveTo(visible.x0, 0)
  ctx.lineTo(visible.x1, 0)
  ctx.setLineDash([12 / view.k, 8 / view.k])
  ctx.strokeStyle = palette.blue
  ctx.lineWidth = 1.5 / view.k
  ctx.stroke()
  ctx.setLineDash([])
}

/** Every asset outline in its accuracy colour; APPROXIMATE dashed, as the 3D outlines. */
function drawAccuracy(input: DrawInput, visible: LocalBox) {
  const { ctx, view } = input
  for (const shape of input.assets) {
    if (shape.accuracy === null || !boxesTouch(shape.box, visible)) continue
    const colour =
      palette.accuracy[shape.accuracy as keyof typeof palette.accuracy] ??
      palette.accuracy.APPROXIMATE
    ctx.setLineDash(shape.accuracy === 'APPROXIMATE' ? [6 / view.k, 4 / view.k] : [])
    strokeShape(ctx, shape, colour, ACCURACY_PX, view.k)
  }
  ctx.setLineDash([])
}

function drawSelection(input: DrawInput) {
  const { ctx, view } = input
  // Shapes with an error get a red outline; the selection draws over it
  for (const shape of input.assets) {
    if (input.errorIds?.has(shape.id)) strokeShape(ctx, shape, palette.brick, OUTLINE_PX, view.k)
  }
  const shapes = [
    ...input.assets.filter((s) => s.id === input.selectedAssetId),
    ...input.background.filter((s) => s.id === input.selectedReferenceId),
  ]
  for (const shape of shapes) {
    if (shape.closed) {
      ctx.fillStyle = withAlpha(palette.select.designFill, 0.3)
      ctx.fill(pathOf(shape), 'evenodd')
    }
    strokeShape(ctx, shape, palette.signal, OUTLINE_PX, view.k)
  }
  const refused = input.preview && !input.preview.valid
  const dragged = refused && input.assets.find((s) => s.id === input.preview?.assetId)
  if (dragged) strokeShape(ctx, dragged, palette.brick, OUTLINE_PX, view.k)
}

/** The handles: white squares, the rotation knob on a stem, smaller squares on the middle of each edge. */
function drawHandles(input: DrawInput, handles: readonly Handle[]) {
  const { ctx, view, size, dpr } = input
  screenTransform(ctx, dpr)
  ctx.lineWidth = 1
  ctx.strokeStyle = palette.ink
  ctx.fillStyle = 'white'
  for (const h of handles) {
    const [px, py] = localToScreen(view, size, h.at[0], h.at[1])
    if (h.kind === 'rotate' && h.stemFrom) {
      const [sx, sy] = localToScreen(view, size, h.stemFrom[0], h.stemFrom[1])
      ctx.beginPath()
      ctx.moveTo(sx, sy)
      ctx.lineTo(px, py)
      ctx.stroke()
      ctx.beginPath()
      ctx.arc(px, py, KNOB_RADIUS_PX, 0, Math.PI * 2)
      ctx.fill()
      ctx.stroke()
      continue
    }
    const side = h.kind === 'mid' ? HANDLE_PX - 4 : HANDLE_PX
    ctx.fillStyle = h.kind === 'mid' ? withAlpha(palette.paper, 0.75) : 'white'
    ctx.fillRect(px - side / 2, py - side / 2, side, side)
    ctx.strokeRect(px - side / 2, py - side / 2, side, side)
  }
}

function drawLabels(input: DrawInput, visible: LocalBox) {
  const { ctx, view, size, dpr } = input
  screenTransform(ctx, dpr)
  ctx.font = '600 11px "Be Vietnam Pro", system-ui, sans-serif'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.lineJoin = 'round'
  ctx.lineWidth = 3
  for (const shape of input.assets) {
    if (!boxesTouch(shape.box, visible) || !labelFits(shape, view.k)) continue
    const px = size.width / 2 + (shape.anchor[0] - view.x) * view.k
    const py = size.height / 2 - (shape.anchor[1] - view.y) * view.k
    ctx.strokeStyle = withAlpha(palette.paper, 0.9)
    ctx.strokeText(shape.name, px, py)
    ctx.fillStyle = palette.ink
    ctx.fillText(shape.name, px, py)
  }
}

/** The sketch: a dashed line through the vertices and the cursor, a faint fill from three points, white square vertices. */
function drawSketch(input: DrawInput, sketch: Sketch) {
  const { ctx, view, size, dpr } = input
  if (sketch.points.length === 0) return
  const path = [...sketch.points, ...(sketch.cursor ? [sketch.cursor] : [])]
  localTransform(ctx, view, size, dpr)
  ctx.beginPath()
  path.forEach(([x, y], i) => (i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y)))
  if (sketch.polygon && sketch.points.length >= 3) {
    ctx.closePath()
    ctx.fillStyle = withAlpha(palette.signal, 0.15)
    ctx.fill()
  }
  ctx.setLineDash([8 / view.k, 5 / view.k])
  ctx.lineJoin = 'round'
  ctx.lineWidth = 2 / view.k
  ctx.strokeStyle = sketch.crossing ? palette.brick : palette.signal
  ctx.stroke()
  ctx.setLineDash([])

  screenTransform(ctx, dpr)
  ctx.lineWidth = 1
  ctx.strokeStyle = palette.ink
  ctx.fillStyle = 'white'
  for (const [x, y] of sketch.points) {
    const [px, py] = localToScreen(view, size, x, y)
    ctx.fillRect(px - VERTEX_PX / 2, py - VERTEX_PX / 2, VERTEX_PX, VERTEX_PX)
    ctx.strokeRect(px - VERTEX_PX / 2, py - VERTEX_PX / 2, VERTEX_PX, VERTEX_PX)
  }
}

/** Paints the whole map: basemap, background outlines, assets bottom to top, quay line, selection and labels. */
/** The placement ghost: fill at alpha 0.45 and a 2 px outline, green or red. */
function drawGhost(input: DrawInput, ghost: Ghost2D) {
  const { ctx, view, size, dpr } = input
  localTransform(ctx, view, size, dpr)
  const colour = ghost.valid ? GHOST_VALID : GHOST_INVALID
  ctx.beginPath()
  const points = ghost.ring ?? ghost.line ?? []
  points.forEach(([x, y], i) => (i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y)))
  if (ghost.ring) ctx.closePath()
  if (ghost.ring) {
    ctx.fillStyle = withAlpha(colour, GHOST_ALPHA)
    ctx.fill()
  }
  ctx.strokeStyle = colour
  ctx.lineWidth = GHOST_OUTLINE_PX / view.k
  ctx.stroke()
}

/** The Measure line: dashed signal orange with white end ticks, the label in a pill above its middle. */
function drawMeasure(input: DrawInput, measure: MeasureDraw) {
  const { ctx, view, size, dpr } = input
  const [ax, ay] = localToScreen(view, size, measure.from[0], measure.from[1])
  const [bx, by] = localToScreen(view, size, measure.to[0], measure.to[1])
  screenTransform(ctx, dpr)
  ctx.beginPath()
  ctx.moveTo(ax, ay)
  ctx.lineTo(bx, by)
  ctx.setLineDash([8, 5])
  ctx.lineWidth = 2
  ctx.strokeStyle = palette.signal
  ctx.stroke()
  ctx.setLineDash([])
  ctx.lineWidth = 1
  ctx.strokeStyle = palette.ink
  ctx.fillStyle = 'white'
  for (const [x, y] of [
    [ax, ay],
    [bx, by],
  ]) {
    ctx.beginPath()
    ctx.arc(x, y, 4, 0, Math.PI * 2)
    ctx.fill()
    ctx.stroke()
  }
  ctx.font = '600 12px "Be Vietnam Pro", system-ui, sans-serif'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  const w = ctx.measureText(measure.label).width + 14
  const mx = (ax + bx) / 2
  const my = (ay + by) / 2 - 16
  ctx.fillStyle = withAlpha(palette.paper, 0.95)
  ctx.strokeStyle = palette.signal
  ctx.beginPath()
  ctx.roundRect(mx - w / 2, my - 11, w, 22, 11)
  ctx.fill()
  ctx.stroke()
  ctx.fillStyle = palette.ink
  ctx.fillText(measure.label, mx, my)
}

export function drawMap(input: DrawInput): void {
  const { ctx, size, dpr, view } = input
  ctx.setTransform(1, 0, 0, 1, 0, 0)
  ctx.fillStyle = palette.land
  ctx.fillRect(0, 0, size.width * dpr, size.height * dpr)

  if (input.basemap === 'sentinel2') drawImagery(input)
  else drawGrid(ctx, view, size, dpr)

  const visible = viewBox(view, size)
  drawBackground(input, visible)
  // The dragged asset is drawn where the pointer has put it; a refused position is faded
  const { preview } = input
  const assets = preview
    ? input.assets.map((shape) => {
        if (shape.id !== preview.assetId) return shape
        return { ...shape, rings: preview.rings, closed: preview.closed, box: boxOf(preview.rings) }
      })
    : input.assets
  const drawn: DrawInput = { ...input, assets }
  localTransform(ctx, view, size, dpr)
  ctx.lineJoin = 'round'
  for (const shape of assets) {
    const refused = preview && !preview.valid && shape.id === preview.assetId
    if (refused) ctx.globalAlpha = 0.7
    if (boxesTouch(shape.box, visible)) drawAsset(ctx, shape, view.k)
    ctx.globalAlpha = 1
  }
  drawQuayLine(input, visible)
  localTransform(ctx, view, size, dpr)
  if (input.accuracy) drawAccuracy(drawn, visible)
  drawSelection(drawn)
  drawLabels(drawn, visible)
  if (input.handles && input.handles.length > 0) drawHandles(input, input.handles)
  if (input.sketch) drawSketch(input, input.sketch)
  if (input.ghost) drawGhost(input, input.ghost)
  if (input.measure) drawMeasure(input, input.measure)
}
