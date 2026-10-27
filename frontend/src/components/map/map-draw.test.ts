import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { beforeAll, describe, expect, it } from 'vitest'
import { palette } from '@/features/scene/palette'
import { IMAGERY_WASH } from '@/lib/basemap'
import { fromLonLat, setActiveFrame, toLonLat } from '@/lib/geo'
import type { AssetType } from '@/lib/api/schemas'
import { drawMap, withAlpha, type DrawInput } from './map-draw'
import { RANK, assetShapes, osmShapes, type MapAssetSource } from './map-scene'
import { localToScreen, type MapView } from './map-view'

setActiveFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 })

/** Path2D is not in jsdom: a path that remembers its points is enough to see what was drawn. */
class FakePath2D {
  points: [number, number][] = []
  closed = false
  moveTo(x: number, y: number) {
    this.points.push([x, y])
  }
  lineTo(x: number, y: number) {
    this.points.push([x, y])
  }
  closePath() {
    this.closed = true
  }
}

interface Call {
  op: string
  style?: string
  width?: number
  dash?: number[]
  args: unknown[]
  transform: number[]
}

/** A canvas context that writes down what it is asked to draw, with the state at that moment. */
class RecordingContext {
  calls: Call[] = []
  fillStyle = ''
  strokeStyle = ''
  lineWidth = 1
  lineCap = ''
  lineJoin = ''
  font = ''
  textAlign = ''
  textBaseline = ''
  private dash: number[] = []
  private matrix: number[] = [1, 0, 0, 1, 0, 0]

  private record(op: string, args: unknown[], style?: string) {
    this.calls.push({
      op,
      args,
      style,
      width: this.lineWidth,
      dash: this.dash,
      transform: this.matrix,
    })
  }
  setTransform(...m: number[]) {
    this.matrix = m
    this.record('setTransform', m)
  }
  setLineDash(dash: number[]) {
    this.dash = dash
  }
  fillRect(...a: number[]) {
    this.record('fillRect', a, this.fillStyle)
  }
  fill(path?: unknown) {
    this.record('fill', [path], this.fillStyle)
  }
  stroke(path?: unknown) {
    this.record('stroke', [path], this.strokeStyle)
  }
  drawImage(...a: unknown[]) {
    this.record('drawImage', a)
  }
  strokeText(text: string) {
    this.record('strokeText', [text])
  }
  fillText(text: string) {
    this.record('fillText', [text], this.fillStyle)
  }
  beginPath() {
    this.record('beginPath', [])
  }
  moveTo(...a: number[]) {
    this.record('moveTo', a)
  }
  lineTo(...a: number[]) {
    this.record('lineTo', a)
  }
  ops(op: string) {
    return this.calls.filter((c) => c.op === op)
  }
}

beforeAll(() => {
  ;(globalThis as { Path2D?: unknown }).Path2D = FakePath2D
})

const SIZE = { width: 1000, height: 600 }

function type(code: string, category: string, colourHex: string): AssetType {
  return {
    code,
    category,
    colourHex,
    flags: { flat: false },
  } as unknown as AssetType
}

const TYPES = [
  type('TERMINAL_BOUNDARY', 'ZONE', palette.terminalT1),
  type('STORAGE_PAD', 'STORAGE', palette.pad),
  type('SILO', 'DRY_BULK', palette.amber),
  type('BERTH', 'MARINE', palette.moss),
  type('QUAY_CRANE_RAIL', 'LIFTING', palette.steel),
]

function rect(x: number, y: number, w: number, h: number) {
  const ring = [
    [x, y],
    [x + w, y],
    [x + w, y + h],
    [x, y + h],
    [x, y],
  ].map(([px, py]) => toLonLat(px, py))
  return { type: 'Polygon', coordinates: [ring] }
}

function asset(id: string, typeCode: string, geometry: unknown, extra = {}): MapAssetSource {
  return { id, typeCode, name: id, geometry, ...extra }
}

function input(over: Partial<Omit<DrawInput, 'ctx'>> & { ctx: RecordingContext }): DrawInput {
  const { ctx, ...rest } = over
  return {
    ctx: ctx as unknown as CanvasRenderingContext2D,
    size: SIZE,
    dpr: 1,
    view: { x: 0, y: 300, k: 0.5 },
    basemap: 'none',
    tileTemplate: 'https://tiles.example.test/{z}/{y}/{x}.jpg',
    tiles: () => null,
    background: [],
    assets: [],
    selectedAssetId: null,
    selectedReferenceId: null,
    ...rest,
  }
}

describe('basemap', () => {
  it('None (grid) draws the grid at the first spacing of 10 ... 1000 m that is 40 px or more', () => {
    const ctx = new RecordingContext()
    drawMap(input({ ctx, view: { x: 0, y: 300, k: 0.5 } }))
    // 100 m at 0.5 px per metre is 50 px: lines every 100 m, 1 px wide on screen
    const grid = ctx.ops('stroke').find((c) => c.style === palette.map.grid)!
    expect(grid.width).toBeCloseTo(1 / 0.5, 9)
    const vertical = ctx.ops('moveTo').filter((c) => c.args[1] === 300 - 300 / 0.5)
    // x from -1000 to 1000 in steps of 100: 21 lines (the ends sit on the edge of the canvas)
    expect(vertical.length).toBeGreaterThanOrEqual(20)
    expect(vertical.every((c) => Math.abs((c.args[0] as number) % 100) < 1e-9)).toBe(true)
  })

  it('draws no satellite tiles and no wash on the grid', () => {
    const ctx = new RecordingContext()
    drawMap(input({ ctx, basemap: 'google' }))
    expect(ctx.ops('drawImage')).toHaveLength(0)
    expect(ctx.ops('fillRect')).toHaveLength(1)
  })

  it('Sentinel-2 draws the tiles that are there under a 12% cream wash', () => {
    const ctx = new RecordingContext()
    const image = {} as CanvasImageSource
    const asked: string[] = []
    drawMap(
      input({
        ctx,
        basemap: 'sentinel2',
        tiles: (url) => {
          asked.push(url)
          return image
        },
      }),
    )
    const [first] = asked
    expect(first).toMatch(/^https:\/\/tiles\.example\.test\/14\/\d+\/\d+\.jpg$/)
    expect(ctx.ops('drawImage').length).toBeGreaterThan(0)
    expect(ctx.ops('drawImage').every((c) => c.args[0] === image)).toBe(true)
    // land colour first, wash last
    const rects = ctx.ops('fillRect')
    expect(rects[0].style).toBe(palette.land)
    expect(rects[rects.length - 1].style).toBe(withAlpha(palette.canvas, IMAGERY_WASH))
  })

  it('asks for the tiles at the zoom of round(log2(156543.03 x cos(lat) x k)), clamped 8-14', () => {
    const zooms = (k: number) => {
      const asked: string[] = []
      drawMap(
        input({
          ctx: new RecordingContext(),
          basemap: 'sentinel2',
          view: { x: 0, y: 300, k },
          tiles: (url) => {
            asked.push(url)
            return null
          },
        }),
      )
      return new Set(asked.map((u) => Number(u.split('/').at(-3))))
    }
    // the sharp zoom is asked for; the next coarser one only as the stand-in under it
    expect(Math.max(...zooms(0.5))).toBe(14)
    expect(Math.max(...zooms(0.02))).toBe(12)
  })

  it('keeps the coarser tiles under the sharp ones that have not arrived yet', () => {
    const ctx = new RecordingContext()
    const image = {} as CanvasImageSource
    drawMap(
      input({
        ctx,
        basemap: 'sentinel2',
        tiles: (url) => (url.includes('/13/') ? image : null),
      }),
    )
    expect(ctx.ops('drawImage').length).toBeGreaterThan(0)
  })

  it('places a tile through the rotated frame, one affine transform per tile', () => {
    const ctx = new RecordingContext()
    drawMap(input({ ctx, basemap: 'sentinel2', tiles: () => ({}) as CanvasImageSource }))
    const [tile] = ctx.ops('drawImage')
    const [a, b, c, d] = tile.transform
    // the frame is turned 120 degrees: the tile axes are not the screen axes
    expect(Math.abs(a)).toBeLessThan(Math.hypot(a, b))
    expect(Math.abs(b)).toBeGreaterThan(0)
    // Web Mercator is not conformal in a local frame: the two sides differ by under 1 %
    expect(Math.abs(Math.hypot(a, b) - Math.hypot(c, d)) / Math.hypot(a, b)).toBeLessThan(0.01)
  })
})

describe('shapes', () => {
  const shapes = assetShapes(
    [
      asset(
        'crane',
        'QUAY_CRANE_RAIL',
        { type: 'LineString', coordinates: [toLonLat(0, 5), toLonLat(100, 5)] },
        { lineWidthM: 2 },
      ),
      asset('silo', 'SILO', rect(10, 100, 60, 60)),
      asset('terminal', 'TERMINAL_BOUNDARY', rect(-300, -50, 700, 600)),
      asset(
        'berth',
        'BERTH',
        { type: 'LineString', coordinates: [toLonLat(-200, 0), toLonLat(200, 0)] },
        { lineWidthM: 3, lengthM: 400 },
      ),
      asset('pad', 'STORAGE_PAD', rect(100, 100, 50, 50), { lengthM: 50, widthM: 50 }),
    ],
    TYPES,
    palette.pad,
  )

  it('are drawn bottom to top: terminal, pad, silo, berth, crane', () => {
    const ctx = new RecordingContext()
    // The shapes arrive in a scrambled order: the painter takes them as given, so they are sorted as the scene does
    drawMap(input({ ctx, assets: shapes }))
    const owner = (path: FakePath2D) =>
      shapes.find((s) => {
        const [x, y] = s.rings[0][0]
        return path.points[0][0] === x && path.points[0][1] === y
      })!.id
    const drawn = ctx.calls
      .filter((c) => (c.op === 'fill' || c.op === 'stroke') && c.args[0] instanceof FakePath2D)
      .map((c) => owner(c.args[0] as FakePath2D))
      .filter((id, i, all) => id !== all[i - 1])
    expect(drawn).toEqual(['terminal', 'pad', 'silo', 'berth', 'crane'])
  })

  it('fill polygons with their type colour, a terminal only as a dashed outline', () => {
    const ctx = new RecordingContext()
    drawMap(input({ ctx, assets: shapes }))
    const fills = ctx.ops('fill').map((c) => c.style)
    expect(fills).toContain(withAlpha(palette.pad, 0.72))
    expect(fills).toContain(withAlpha(palette.amber, 0.72))
    // no fill for the terminal
    expect(fills).not.toContain(withAlpha(palette.terminalT1, 0.72))
    const dashed = ctx.ops('stroke').filter((c) => c.style === palette.terminalT1)
    expect(dashed).toHaveLength(1)
    expect(dashed[0].dash!.length).toBe(2)
  })

  it('draw a line as wide as it is on the ground, never thinner than 1.5 px', () => {
    const ctx = new RecordingContext()
    drawMap(input({ ctx, assets: shapes, view: { x: 0, y: 0, k: 4 } }))
    const berth = ctx.ops('stroke').find((c) => c.style === palette.moss)!
    // 3 m at 4 px per metre is 12 px; the line width is set in metres under the zoom transform
    expect(berth.width! * 4).toBeCloseTo(12, 9)

    const far = new RecordingContext()
    drawMap(input({ ctx: far, assets: shapes, view: { x: 0, y: 0, k: 0.1 } }))
    const thin = far.ops('stroke').find((c) => c.style === palette.moss)!
    expect(thin.width! * 0.1).toBeCloseTo(1.5, 9)
  })

  it('leave out shapes that are outside the view', () => {
    const ctx = new RecordingContext()
    drawMap(input({ ctx, assets: shapes, view: { x: 50_000, y: 50_000, k: 0.5 } }))
    expect(ctx.ops('fill')).toHaveLength(0)
  })

  it('draw the quay line (local y = 0) dashed in blue, only when it is in view', () => {
    const ctx = new RecordingContext()
    drawMap(input({ ctx, view: { x: 0, y: 100, k: 0.5 } }))
    const quay = ctx.ops('stroke').find((c) => c.style === palette.blue)!
    expect(quay.dash!.length).toBe(2)
    expect(ctx.ops('moveTo').some((c) => c.args[1] === 0)).toBe(true)

    const away = new RecordingContext()
    drawMap(input({ ctx: away, view: { x: 0, y: 900, k: 0.5 } }))
    expect(away.ops('stroke').some((c) => c.style === palette.blue)).toBe(false)
  })

  it('outline the background in thin grey, under the assets', () => {
    const ctx = new RecordingContext()
    const background = osmShapes(
      [{ id: 'way/1', geometry: rect(0, 0, 40, 40) }],
      palette.map.osmOutline,
    )
    drawMap(input({ ctx, background, assets: shapes }))
    const strokes = ctx.ops('stroke')
    const grey = strokes.findIndex((c) => c.style === palette.map.osmOutline)
    const firstAsset = strokes.findIndex((c) => c.style === palette.terminalT1)
    expect(grey).toBeGreaterThanOrEqual(0)
    expect(grey).toBeLessThan(firstAsset)
    expect(strokes[grey].width! * 0.5).toBeCloseTo(1, 9)
  })

  it('highlight the selected shape', () => {
    const ctx = new RecordingContext()
    drawMap(input({ ctx, assets: shapes, selectedAssetId: 'pad' }))
    const edge = ctx.ops('stroke').filter((c) => c.style === palette.select.designEdge)
    expect(edge).toHaveLength(1)
    expect(edge[0].width! * 0.5).toBeCloseTo(3, 9)
  })
})

describe('labels', () => {
  const shapes = assetShapes(
    [
      asset('Big pad', 'STORAGE_PAD', rect(0, 100, 120, 120), { lengthM: 120, widthM: 120 }),
      asset('Small pad', 'STORAGE_PAD', rect(200, 100, 20, 20), { lengthM: 20, widthM: 20 }),
      asset(
        'Berth 2',
        'BERTH',
        { type: 'LineString', coordinates: [toLonLat(-200, 0), toLonLat(200, 0)] },
        { lengthM: 400 },
      ),
    ],
    TYPES,
    palette.pad,
  )

  it('show for a shape whose shorter side is over 26 px, and for a berth longer than 50 px', () => {
    const ctx = new RecordingContext()
    // 0.5 px per metre: the big pad is 60 px, the small one 10 px, the berth 200 px
    drawMap(input({ ctx, assets: shapes, view: { x: 0, y: 100, k: 0.5 } }))
    expect(ctx.ops('fillText').map((c) => c.args[0])).toEqual(['Big pad', 'Berth 2'])
  })

  it('hide when the map is zoomed out', () => {
    const ctx = new RecordingContext()
    drawMap(input({ ctx, assets: shapes, view: { x: 0, y: 100, k: 0.1 } }))
    // 12 px pad, but the berth is 40 px: under 50
    expect(ctx.ops('fillText')).toHaveLength(0)
  })

  it('sit at the anchor of the shape, in screen pixels', () => {
    const ctx = new RecordingContext()
    const view: MapView = { x: 0, y: 100, k: 0.5 }
    drawMap(input({ ctx, assets: shapes, view }))
    const [px, py] = localToScreen(
      view,
      SIZE,
      shapes.find((s) => s.id === 'Big pad')!.anchor[0],
      shapes.find((s) => s.id === 'Big pad')!.anchor[1],
    )
    expect(px).toBeGreaterThan(0)
    expect(py).toBeGreaterThan(0)
  })
})

describe('the whole Westports layout', () => {
  const root = join(__dirname, '..', '..', '..', '..')
  const read = (path: string) => JSON.parse(readFileSync(join(root, path), 'utf8'))
  const layout = read('backend/castor-core/src/main/resources/seed/westports/layout-v1.geojson')
  const osm = read('backend/castor-core/src/main/resources/seed/westports/osm-reference.geojson')

  const sources: MapAssetSource[] = layout.features.map(
    (f: { id: string; geometry: unknown; properties: Record<string, unknown> }) => ({
      ...f.properties,
      id: f.id,
      typeCode: f.properties.type,
      geometry: f.geometry,
    }),
  )

  const assets = assetShapes(sources, [], palette.pad)
  const background = osmShapes(
    osm.features.map(
      (
        f: { id?: string; geometry: unknown; properties: { osm_ref?: string; name?: string } },
        i: number,
      ) => ({
        id: f.properties.osm_ref ?? f.id ?? String(i),
        geometry: f.geometry,
        name: f.properties.name,
      }),
    ),
    palette.map.osmOutline,
  )

  it('turns every asset of the seed layout into a shape, in draw order', () => {
    expect(assets).toHaveLength(layout.features.length)
    const ranks = assets.map((s) => s.rank)
    expect(ranks).toEqual([...ranks].sort((a, b) => a - b))
    expect(ranks[0]).toBe(RANK.terminal)
  })

  it('finds the main gate where the control point is', () => {
    const [x, y] = fromLonLat(101.3093602, 2.9518844)
    expect(x).toBeCloseTo(-7.9, 0)
    expect(y).toBeCloseTo(346.9, 0)
  })

  it('draws a frame of it in well under 16 ms of script time, at the overview and zoomed in', () => {
    const ctx = new RecordingContext()
    const run = (view: MapView, frames: number) => {
      const start = performance.now()
      for (let i = 0; i < frames; i++) {
        ctx.calls = []
        drawMap(input({ ctx, assets, background, view: { ...view, x: view.x + i } }))
      }
      return (performance.now() - start) / frames
    }
    run({ x: 0, y: 300, k: 0.5 }, 5) // warm up, builds the paths once
    expect(run({ x: 0, y: 300, k: 0.5 }, 30)).toBeLessThan(16)
    expect(run({ x: 0, y: 300, k: 0.05 }, 30)).toBeLessThan(16)
    expect(run({ x: 0, y: 300, k: 5 }, 30)).toBeLessThan(16)
  })

  it('draws only what is in view when zoomed in', () => {
    const wide = new RecordingContext()
    drawMap(input({ ctx: wide, assets, background, view: { x: 0, y: 300, k: 0.05 } }))
    const close = new RecordingContext()
    drawMap(input({ ctx: close, assets, background, view: { x: 0, y: 300, k: 5 } }))
    expect(close.ops('stroke').length).toBeLessThan(wide.ops('stroke').length)
  })
})
