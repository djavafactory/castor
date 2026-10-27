import type { Local, LonLat } from '@/lib/geo'

/** Web Mercator tile maths of the Sentinel-2 cloudless basemap (SETUP-03). */

export const TILE_PX = 256
export const FAR_ZOOM = 13
export const NEAR_ZOOM = 14
/** Closer than this to the target the sharper zoom is used. */
export const NEAR_DISTANCE_M = 1500
/** One canvas texture is at most this many pixels on a side. */
export const MAX_CANVAS_PX = 4096
/** Source pixels are drawn this much larger so the rotated tiles stay smooth. */
const OVERSAMPLE = 2
/** Tiles are drawn slightly larger than their cell so no seam shows. */
export const SEAM_OVERLAP_PX = 0.6

export interface TileId {
  z: number
  x: number
  y: number
}

const RAD = Math.PI / 180

export function lonLatToTile(lon: number, lat: number, z: number): { x: number; y: number } {
  const n = 2 ** z
  const latRad = lat * RAD
  return {
    x: Math.floor(((lon + 180) / 360) * n),
    y: Math.floor(((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2) * n),
  }
}

/** Longitude and latitude of the north-west corner of tile (x, y) at zoom z. */
export function tileCorner(x: number, y: number, z: number): LonLat {
  const n = 2 ** z
  return [(x / n) * 360 - 180, Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / n))) / RAD]
}

export function zoomForDistance(distance: number): number {
  return distance < NEAR_DISTANCE_M ? NEAR_ZOOM : FAR_ZOOM
}

/** The tile URL template has {z}, {y} and {x} (note the y before x of the Sentinel-2 WMTS). */
export function tileUrl(template: string, t: TileId): string {
  return template
    .replace('{z}', String(t.z))
    .replace('{y}', String(t.y))
    .replace('{x}', String(t.x))
}

/** One canvas of the ground: a rectangle of local metres and its pixel size. */
export interface Cell {
  x0: number
  x1: number
  y0: number
  y1: number
  /** Canvas pixels per metre. */
  ppm: number
  width: number
  height: number
}

/** Metres on the ground of one source pixel at latitude lat and zoom z. */
export function metresPerPixel(lat: number, z: number): number {
  return (156543.03392 * Math.cos(lat * RAD)) / 2 ** z
}

/** Splits a local rectangle into canvas cells of at most 4096 px, sharpness following the zoom. */
export function planCells(
  area: { x0: number; x1: number; y0: number; y1: number },
  lat: number,
  z: number,
): Cell[] {
  const ppm = OVERSAMPLE / metresPerPixel(lat, z)
  const cellM = MAX_CANVAS_PX / ppm
  const cols = Math.max(1, Math.ceil((area.x1 - area.x0) / cellM))
  const rows = Math.max(1, Math.ceil((area.y1 - area.y0) / cellM))
  const w = (area.x1 - area.x0) / cols
  const h = (area.y1 - area.y0) / rows
  const cells: Cell[] = []
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      cells.push({
        x0: area.x0 + c * w,
        x1: area.x0 + (c + 1) * w,
        y0: area.y0 + r * h,
        y1: area.y0 + (r + 1) * h,
        ppm,
        width: Math.max(1, Math.ceil(w * ppm)),
        height: Math.max(1, Math.ceil(h * ppm)),
      })
    }
  }
  return cells
}

/** Tiles whose footprint can touch a lon/lat bounding box. */
export function tilesCovering(corners: readonly LonLat[], z: number): TileId[] {
  const lons = corners.map((c) => c[0])
  const lats = corners.map((c) => c[1])
  const nw = lonLatToTile(Math.min(...lons), Math.max(...lats), z)
  const se = lonLatToTile(Math.max(...lons), Math.min(...lats), z)
  const out: TileId[] = []
  for (let y = nw.y; y <= se.y; y++) for (let x = nw.x; x <= se.x; x++) out.push({ z, x, y })
  return out
}

/** The canvas transform that draws a 256 px tile image into a cell (handles the frame rotation). */
export function tileTransform(
  cell: Cell,
  nw: Local,
  ne: Local,
  sw: Local,
): [a: number, b: number, c: number, d: number, e: number, f: number] {
  const k = cell.ppm / TILE_PX
  return [
    (ne[0] - nw[0]) * k,
    -(ne[1] - nw[1]) * k,
    (sw[0] - nw[0]) * k,
    -(sw[1] - nw[1]) * k,
    (nw[0] - cell.x0) * cell.ppm,
    (cell.y1 - nw[1]) * cell.ppm,
  ]
}

/** Tile image cache of about 400 tiles, least recently used first out. */
export class TileCache {
  private readonly images = new Map<string, Promise<CanvasImageSource | null>>()
  private readonly load: (url: string) => Promise<CanvasImageSource | null>
  private readonly capacity: number

  constructor(load: (url: string) => Promise<CanvasImageSource | null>, capacity = 400) {
    this.load = load
    this.capacity = capacity
  }

  get(url: string): Promise<CanvasImageSource | null> {
    const hit = this.images.get(url)
    if (hit) {
      this.images.delete(url)
      this.images.set(url, hit)
      return hit
    }
    const loading = this.load(url)
    this.images.set(url, loading)
    while (this.images.size > this.capacity) {
      const oldest = this.images.keys().next().value
      if (oldest === undefined) break
      this.images.delete(oldest)
    }
    return loading
  }

  get size(): number {
    return this.images.size
  }
}

/** Loads one tile; a tile that fails resolves to null so the land colour stays. */
export function loadTileImage(url: string): Promise<CanvasImageSource | null> {
  return new Promise((resolve) => {
    const image = new Image()
    image.crossOrigin = 'anonymous'
    image.onload = () => resolve(image)
    image.onerror = () => resolve(null)
    image.src = url
  })
}

/**
 * Paints the tiles of one cell on a canvas: the land colour first, then every tile through its affine transform
 * (three corners converted with the geo module). Resolves to how many tiles were drawn.
 */
export async function paintCell(
  canvas: HTMLCanvasElement,
  cell: Cell,
  tiles: readonly TileId[],
  opts: {
    template: string
    background: string
    cache: TileCache
    toLocal: (lon: number, lat: number) => Local
    signal?: AbortSignal
  },
): Promise<number> {
  canvas.width = cell.width
  canvas.height = cell.height
  const ctx = canvas.getContext('2d')
  if (!ctx) return 0
  ctx.fillStyle = opts.background
  ctx.fillRect(0, 0, cell.width, cell.height)
  let drawn = 0
  await Promise.all(
    tiles.map(async (t) => {
      const nw = opts.toLocal(...tileCorner(t.x, t.y, t.z))
      const ne = opts.toLocal(...tileCorner(t.x + 1, t.y, t.z))
      const sw = opts.toLocal(...tileCorner(t.x, t.y + 1, t.z))
      const se: Local = [ne[0] + sw[0] - nw[0], ne[1] + sw[1] - nw[1]]
      const xs = [nw[0], ne[0], sw[0], se[0]]
      const ys = [nw[1], ne[1], sw[1], se[1]]
      if (
        Math.max(...xs) < cell.x0 ||
        Math.min(...xs) > cell.x1 ||
        Math.max(...ys) < cell.y0 ||
        Math.min(...ys) > cell.y1
      ) {
        return
      }
      const image = await opts.cache.get(tileUrl(opts.template, t))
      if (!image || opts.signal?.aborted) return
      ctx.save()
      ctx.setTransform(...tileTransform(cell, nw, ne, sw))
      const o = SEAM_OVERLAP_PX
      ctx.drawImage(image, -o, -o, TILE_PX + 2 * o, TILE_PX + 2 * o)
      ctx.restore()
      drawn++
    }),
  )
  return drawn
}
