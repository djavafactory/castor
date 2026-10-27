import { describe, expect, it } from 'vitest'
import { cursorReadout, northNeedleDeg, scaleBar } from './map-furniture'

describe('scale bar', () => {
  it('is the first of 5, 10, 20, 50 ... 5000 m that is at least 80 px', () => {
    expect(scaleBar(0.5)).toEqual({ metres: 200, px: 100, label: '200 m' })
    expect(scaleBar(20)).toEqual({ metres: 5, px: 100, label: '5 m' })
    expect(scaleBar(1)).toEqual({ metres: 100, px: 100, label: '100 m' })
    expect(scaleBar(0.4)).toEqual({ metres: 200, px: 80, label: '200 m' })
  })

  it('says km from 1000 m up', () => {
    expect(scaleBar(0.1)).toEqual({ metres: 1000, px: 100, label: '1 km' })
    expect(scaleBar(0.02)).toEqual({ metres: 5000, px: 100, label: '5 km' })
    expect(scaleBar(0.05).label).toBe('2 km')
  })

  it('is at least 80 px and under 400 px over the whole zoom range', () => {
    for (let k = 0.02; k <= 20; k *= 1.07) {
      const bar = scaleBar(k)
      expect(bar.px).toBeGreaterThanOrEqual(80)
      expect(bar.px).toBeLessThan(400)
    }
  })
})

describe('north arrow', () => {
  it('turns by the rotation of the quay frame', () => {
    expect(northNeedleDeg(-120.299)).toBe(-120.299)
    expect(northNeedleDeg(0)).toBe(0)
  })
})

describe('cursor readout', () => {
  it('has latitude and longitude to 7 places and local metres to 1', () => {
    expect(cursorReadout(101.3093602, 2.9518844, -7.9, 346.9)).toBe(
      '2.9518844, 101.3093602  (x -7.9, y 346.9 m)',
    )
    expect(cursorReadout(101.3, 2.95, 1234.56, -0.04)).toBe(
      '2.9500000, 101.3000000  (x 1234.6, y 0.0 m)',
    )
  })
})
