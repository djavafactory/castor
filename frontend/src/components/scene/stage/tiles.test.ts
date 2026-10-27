import { palette } from '@/features/scene/palette'
import { createFrame } from '@/lib/geo'
import {
  FAR_ZOOM,
  NEAR_ZOOM,
  TileCache,
  lonLatToTile,
  metresPerPixel,
  paintCell,
  planCells,
  tileCorner,
  tileTransform,
  tileUrl,
  tilesCovering,
  zoomForDistance,
} from './tiles'

describe('Web Mercator tiles', () => {
  it('finds the tile of a position and its corner', () => {
    // Westports origin at zoom 13
    const t = lonLatToTile(101.3066304, 2.9534057, 13)
    expect(t).toEqual({ x: 6401, y: 4028 })
    const [lon, lat] = tileCorner(t.x, t.y, 13)
    expect(lon).toBeLessThanOrEqual(101.3066304)
    expect(lat).toBeGreaterThanOrEqual(2.9534057)
    const [lon2, lat2] = tileCorner(t.x + 1, t.y + 1, 13)
    expect(lon2).toBeGreaterThan(101.3066304)
    expect(lat2).toBeLessThan(2.9534057)
  })

  it('has the world corners right', () => {
    expect(tileCorner(0, 0, 0)[0]).toBe(-180)
    expect(tileCorner(0, 0, 0)[1]).toBeCloseTo(85.0511, 3)
    expect(tileCorner(1, 1, 1)[0]).toBe(0)
    expect(tileCorner(1, 1, 1)[1]).toBeCloseTo(0, 9)
  })

  it('uses zoom 14 only closer than 1,500 m', () => {
    expect(zoomForDistance(1700)).toBe(FAR_ZOOM)
    expect(zoomForDistance(1500)).toBe(FAR_ZOOM)
    expect(zoomForDistance(1499)).toBe(NEAR_ZOOM)
    expect([FAR_ZOOM, NEAR_ZOOM]).toEqual([13, 14])
  })

  it('fills the {z}/{y}/{x} template', () => {
    expect(tileUrl('https://t/{z}/{y}/{x}.jpg', { z: 14, x: 5, y: 7 })).toBe('https://t/14/7/5.jpg')
  })

  it('covers a lon/lat box with tiles', () => {
    const tiles = tilesCovering(
      [
        [101.27, 2.99],
        [101.34, 2.9],
      ],
      13,
    )
    expect(tiles.length).toBeGreaterThan(1)
    expect(new Set(tiles.map((t) => t.z))).toEqual(new Set([13]))
  })

  it('splits the site into canvases of at most 4096 px', () => {
    const lat = 2.95
    const area = { x0: -3800, x1: 6300, y0: -2000, y1: 1800 }
    for (const z of [FAR_ZOOM, NEAR_ZOOM]) {
      const cells = planCells(area, lat, z)
      expect(cells.length).toBeGreaterThanOrEqual(1)
      for (const c of cells) {
        expect(c.width).toBeLessThanOrEqual(4096)
        expect(c.height).toBeLessThanOrEqual(4096)
      }
      // the cells tile the area exactly
      const w = cells.reduce((s, c) => (c.y0 === area.y0 ? s + (c.x1 - c.x0) : s), 0)
      expect(w).toBeCloseTo(area.x1 - area.x0, 6)
    }
    expect(metresPerPixel(lat, 14)).toBeLessThan(metresPerPixel(lat, 13))
    // a huge area needs several canvases
    expect(planCells({ x0: 0, x1: 100_000, y0: 0, y1: 100_000 }, lat, 14).length).toBeGreaterThan(4)
  })

  it('draws a tile through the affine transform of three corners (rotated frame)', () => {
    const frame = createFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 })
    const t = { z: 14, x: 12908, y: 8132 }
    const nw = frame.fromLonLat(...tileCorner(t.x, t.y, t.z))
    const ne = frame.fromLonLat(...tileCorner(t.x + 1, t.y, t.z))
    const sw = frame.fromLonLat(...tileCorner(t.x, t.y + 1, t.z))
    const cell = { x0: -500, x1: 500, y0: -500, y1: 500, ppm: 2, width: 2000, height: 2000 }
    const [a, b, c, d, e, f] = tileTransform(cell, nw, ne, sw)
    // image (0,0) lands on the north-west corner, (256,0) on the north-east, (0,256) on the south-west
    const map = (i: number, j: number) => [a * i + c * j + e, b * i + d * j + f]
    expect(map(0, 0)).toEqual([(nw[0] + 500) * 2, (500 - nw[1]) * 2])
    expect(map(256, 0)[0]).toBeCloseTo((ne[0] + 500) * 2, 6)
    expect(map(256, 0)[1]).toBeCloseTo((500 - ne[1]) * 2, 6)
    expect(map(0, 256)[0]).toBeCloseTo((sw[0] + 500) * 2, 6)
    expect(map(0, 256)[1]).toBeCloseTo((500 - sw[1]) * 2, 6)
    // the frame is rotated, so the tile is not axis aligned
    expect(Math.abs(b)).toBeGreaterThan(0.01)
  })
})

describe('TileCache', () => {
  it('keeps about 400 tiles, dropping the least recently used', async () => {
    const load = vi.fn(() => Promise.resolve({} as CanvasImageSource))
    const cache = new TileCache(load, 3)
    await cache.get('a')
    await cache.get('b')
    await cache.get('c')
    await cache.get('a') // a is fresh again
    await cache.get('d') // b goes
    expect(cache.size).toBe(3)
    load.mockClear()
    await cache.get('a')
    expect(load).not.toHaveBeenCalled()
    await cache.get('b')
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('holds 400 tiles by default', async () => {
    const cache = new TileCache(() => Promise.resolve(null))
    for (let i = 0; i < 450; i++) await cache.get(`t${i}`)
    expect(cache.size).toBe(400)
  })
})

describe('paintCell', () => {
  function fakeCanvas() {
    const calls: string[] = []
    const ctx = {
      fillStyle: '',
      fillRect: () => calls.push('fill'),
      save: () => undefined,
      restore: () => undefined,
      setTransform: () => calls.push('transform'),
      drawImage: (_i: unknown, x: number, y: number, w: number) =>
        calls.push(`draw ${x} ${y} ${w}`),
    }
    const canvas = { width: 0, height: 0, getContext: () => ctx } as unknown as HTMLCanvasElement
    return { canvas, calls }
  }
  const cell = { x0: -100, x1: 100, y0: -100, y1: 100, ppm: 1, width: 200, height: 200 }
  it('paints the land colour, then draws a tile 0.6 px larger on every side', async () => {
    const { canvas, calls } = fakeCanvas()
    const cache = new TileCache(() => Promise.resolve({} as CanvasImageSource))
    const drawn = await paintCell(canvas, cell, [{ z: 14, x: 12908, y: 8132 }], {
      template: 'u/{z}/{y}/{x}',
      background: palette.land,
      cache,
      toLocal: () => [0, 0],
    })
    expect(drawn).toBe(1)
    expect(calls).toEqual(['fill', 'transform', 'draw -0.6 -0.6 257.2'])
  })

  it('stays the land colour when a tile fails', async () => {
    const { canvas, calls } = fakeCanvas()
    const cache = new TileCache(() => Promise.resolve(null))
    const drawn = await paintCell(canvas, cell, [{ z: 14, x: 1, y: 1 }], {
      template: 'u/{z}/{y}/{x}',
      background: palette.land,
      cache,
      toLocal: () => [0, 0],
    })
    expect(drawn).toBe(0)
    expect(calls).toEqual(['fill'])
  })
})
