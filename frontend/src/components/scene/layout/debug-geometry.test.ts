import { Vector3 } from 'three'
import { currentLayoutSchema } from '@/lib/api'
import { clearActiveFrame, setActiveFrame } from '@/lib/geo'
import { currentLayoutFixture } from '@/test/fixtures'
import { MIN_ENVELOPE_HEIGHT } from '../models/envelope'
import { getModelSpec } from '../models/registry'
import { buildEnvelopeGeometry, buildOutlineBatches, OUTLINE_LIFT_M } from './debug-geometry'
import { placeLayout } from './placement'

const FRAME = { lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 }
const features = currentLayoutSchema.parse(currentLayoutFixture).features.features

beforeEach(() => setActiveFrame(FRAME))
afterEach(clearActiveFrame)

describe('envelope boxes', () => {
  it('has one box per pick box of every asset, never flatter than 0.3 m', () => {
    const placed = placeLayout(features)
    expect(placed.envelopes.length).toBe(placed.picks.length)
    for (const box of placed.envelopes) {
      expect(box.size.height).toBeGreaterThanOrEqual(MIN_ENVELOPE_HEIGHT)
    }
  })

  it('is as tall as the drawn model of the asset, crane mast included', () => {
    const placed = placeLayout(features)
    const sts = placed.envelopes.find((b) => b.assetId === 'sts1')!
    const spec = getModelSpec('STS_CRANE')!
    const size = placed.cranes.find((c) => c.id === 'sts1')!.size
    expect(sts.size.height).toBeCloseTo(
      ((spec.size.height + spec.extraHeight) * size.height) / spec.size.height,
      3,
    )
  })

  it('stands on the elevation of its asset, turned with it', () => {
    const placed = placeLayout(features)
    const pick = placed.picks.find((b) => b.assetId === 'wh1')!
    const envelope = placed.envelopes.find((b) => b.assetId === 'wh1')!
    expect(envelope.rotationY).toBe(pick.rotationY)
    const geometry = buildEnvelopeGeometry([envelope])!
    geometry.computeBoundingBox()
    const box = geometry.boundingBox!
    expect(box.min.y).toBeCloseTo(envelope.position[1] - envelope.size.height / 2)
    expect(box.max.y).toBeCloseTo(envelope.position[1] + envelope.size.height / 2)
    const centre = box.getCenter(new Vector3())
    expect(centre.x).toBeCloseTo(envelope.position[0], 3)
    expect(centre.z).toBeCloseTo(envelope.position[2], 3)
    geometry.dispose()
  })

  it('draws nothing without boxes', () => {
    expect(buildEnvelopeGeometry([])).toBeNull()
  })
})

describe('accuracy outlines', () => {
  it('gives every asset an outline with its accuracy; a path stays open', () => {
    const placed = placeLayout(features)
    expect(placed.outlines.map((o) => o.assetId).sort()).toEqual(features.map((f) => f.id).sort())
    expect(placed.outlines.find((o) => o.assetId === 'wh1')).toMatchObject({
      closed: true,
      accuracy: expect.any(String),
    })
    expect(placed.outlines.find((o) => o.assetId === 'rd1')!.closed).toBe(false)
  })

  it('draws one batch per accuracy, only APPROXIMATE dashed', () => {
    const batches = buildOutlineBatches([
      {
        assetId: 'a',
        accuracy: 'SURVEYED',
        closed: true,
        points: [
          [0, 0],
          [10, 0],
          [10, 10],
        ],
      },
      {
        assetId: 'b',
        accuracy: 'APPROXIMATE',
        closed: false,
        points: [
          [0, 0],
          [5, 0],
          [5, 5],
        ],
      },
    ])
    expect(batches.map((b) => [b.accuracy, b.dashed])).toEqual([
      ['SURVEYED', false],
      ['APPROXIMATE', true],
    ])
    // A closed triangle has three edges, an open line of three points two
    expect(batches[0]!.geometry.getAttribute('position').count).toBe(6)
    expect(batches[1]!.geometry.getAttribute('position').count).toBe(4)
    expect(batches[0]!.geometry.getAttribute('position').getY(0)).toBeCloseTo(OUTLINE_LIFT_M)
    batches.forEach((b) => b.geometry.dispose())
  })
})
