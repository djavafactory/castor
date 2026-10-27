import { clearActiveFrame, setActiveFrame } from '@/lib/geo'
import { currentLayoutSchema } from '@/lib/api'
import { currentLayoutFixture } from '@/test/fixtures'
import { buildPickGeometry } from './pick-geometry'
import { placeLayout } from './placement'

const FRAME = { lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 }

beforeEach(() => setActiveFrame(FRAME))
afterEach(clearActiveFrame)

const features = currentLayoutSchema.parse(currentLayoutFixture).features.features

describe('placeLayout', () => {
  let placed: ReturnType<typeof placeLayout>
  beforeEach(() => {
    placed = placeLayout(features)
  })

  it('sends container yards and their cranes to the container layer, the rest to the library', () => {
    expect(placed.yards.map((y) => y.id)).toEqual(['cy1'])
    expect(placed.cranes.map((c) => c.id)).toEqual(['sts1'])
    expect(placed.assets.map((a) => a.id)).toEqual(['wh1', 'fk1', 'rd1'])
    expect(placed.unknown.map((u) => u.id)).toEqual(['x1'])
  })

  it('takes size, tiers and fill from the asset', () => {
    expect(placed.yards[0]).toMatchObject({
      code: 'CY1',
      length: 120,
      width: 60,
      maxTiers: 4,
      designFill: 0.5,
    })
    expect(placed.assets[0]!.asset.size).toEqual({ length: 60, width: 30, height: 12 })
    expect(placed.assets[0]!.asset.details).toMatchObject({ doors: 4, roofRise: 2 })
  })

  it('falls back to the default size of the model when the asset has none', () => {
    const forklift = placed.assets.find((a) => a.id === 'fk1')!.asset
    expect(forklift.size.length).toBeGreaterThan(0)
    expect(forklift.size.height).toBeGreaterThan(0)
  })

  it('places the anchor through the port frame: the origin is the scene origin', () => {
    const origin = placeLayout([
      {
        ...features[3]!,
        properties: { ...features[3]!.properties, anchor: [FRAME.lon0, FRAME.lat0] },
      },
    ])
    expect(origin.assets[0]!.asset.position[0]).toBeCloseTo(0, 6)
    expect(origin.assets[0]!.asset.position[2]).toBeCloseTo(0, 6)
  })

  it('turns by the heading and draws the polygon open and around the anchor', () => {
    const wh = placed.assets[0]!.asset
    expect(wh.rotationY).not.toBe(0)
    expect(wh.footprint).toHaveLength(4)
    const sum = wh.footprint!.reduce((a, p) => [a[0] + p[0], a[1] + p[1]], [0, 0])
    expect(sum[0] / 4).toBeCloseTo(0, 1)
    expect(sum[1] / 4).toBeCloseTo(0, 1)
  })

  it('has no heading turn for an asset without heading', () => {
    expect(placed.yards[0]!.heading).toBe(0)
  })

  it('makes one pick box per path segment and one per other asset', () => {
    const forRoad = placed.picks.filter((b) => b.assetId === 'rd1')
    expect(forRoad).toHaveLength(2)
    expect(placed.picks.filter((b) => b.assetId === 'wh1')).toHaveLength(1)
    expect(placed.picks).toHaveLength(7)
  })
})

describe('buildPickGeometry', () => {
  it('maps a hit triangle back to its asset', () => {
    const { picks } = placeLayout(features)
    const pick = buildPickGeometry(picks)!
    expect(pick.assetAtFace(0)).toBe(picks[0]!.assetId)
    expect(pick.assetAtFace(11)).toBe(picks[0]!.assetId)
    expect(pick.assetAtFace(12 * 2)).toBe(picks[2]!.assetId)
    expect(pick.assetAtFace(12 * picks.length)).toBeUndefined()
    pick.geometry.dispose()
  })

  it('has nothing to pick in an empty layout', () => {
    expect(buildPickGeometry([])).toBeNull()
  })
})
