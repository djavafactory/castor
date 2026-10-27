import { beforeAll, describe, expect, it } from 'vitest'
import { setActiveFrame, toLonLat } from '@/lib/geo'
import {
  MAX_DISTANCE_FROM_ORIGIN_M,
  OUTSIDE_PORT_MESSAGE,
  OUT_OF_RANGE_MESSAGE,
  parseCoordinates,
} from './coordinates'

beforeAll(() => {
  setActiveFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 })
})

describe('pasted coordinates', () => {
  it('read "lat, lon" and go to the place with the row of the ticket', () => {
    const result = parseCoordinates('2.9518844, 101.3093602')
    expect(result).toMatchObject({
      kind: 'go',
      lat: 2.9518844,
      lon: 101.3093602,
      label: 'Go to 2.9518844, 101.3093602 (lat, lon)',
    })
  })

  it('land on the main gate: its local position is the one of the control point', () => {
    const result = parseCoordinates('2.9518844, 101.3093602')
    if (result.kind !== 'go') throw new Error('not a place')
    // control point CP-MAIN-GATE expects local (-7.9, 346.9)
    expect(result.x).toBeCloseTo(-7.9, 0)
    expect(result.y).toBeCloseTo(346.9, 0)
    const [lon, lat] = toLonLat(result.x, result.y)
    expect(lon).toBeCloseTo(101.3093602, 9)
    expect(lat).toBeCloseTo(2.9518844, 9)
  })

  it('accept a comma, spaces or both, and negative or whole numbers', () => {
    for (const text of ['2.95,101.3', '2.95 101.3', '  2.95 ,  101.3  ', '2.95\t101.3']) {
      expect(parseCoordinates(text).kind).toBe('go')
    }
    expect(parseCoordinates('-2.95, 101')).toMatchObject({ kind: 'error' })
    expect(parseCoordinates('3, 101.3')).toMatchObject({ kind: 'go', lat: 3, lon: 101.3 })
  })

  it('are not coordinates when the text is anything else', () => {
    for (const text of [
      '',
      'berth 2',
      'gate',
      '2.95',
      '2.95,',
      '2.95, 101.3, 4',
      '2.95; 101.3',
      '1e3, 5',
    ]) {
      expect(parseCoordinates(text)).toEqual({ kind: 'none' })
    }
  })

  it('say when latitude or longitude is out of range', () => {
    expect(OUT_OF_RANGE_MESSAGE).toBe('Latitude must be -90..90 and longitude -180..180')
    expect(parseCoordinates('91, 101')).toEqual({ kind: 'error', message: OUT_OF_RANGE_MESSAGE })
    expect(parseCoordinates('2.95, 181')).toEqual({ kind: 'error', message: OUT_OF_RANGE_MESSAGE })
    expect(parseCoordinates('-90.1, 0')).toEqual({ kind: 'error', message: OUT_OF_RANGE_MESSAGE })
  })

  it('say when the place is more than 20 km from the origin', () => {
    expect(OUTSIDE_PORT_MESSAGE).toBe(
      'That place is outside this port layout. Search is a locator only.',
    )
    expect(MAX_DISTANCE_FROM_ORIGIN_M).toBe(20_000)
    // Kuala Lumpur is about 35 km away
    expect(parseCoordinates('3.139, 101.6869')).toEqual({
      kind: 'error',
      message: OUTSIDE_PORT_MESSAGE,
    })
    // 0.1 degree of latitude is about 11 km: still this port
    expect(parseCoordinates('3.0534, 101.3066').kind).toBe('go')
  })

  it('check the range before the distance', () => {
    expect(parseCoordinates('200, 500')).toEqual({ kind: 'error', message: OUT_OF_RANGE_MESSAGE })
  })
})
