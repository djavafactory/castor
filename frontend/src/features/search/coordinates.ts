import { fromLonLat } from '@/lib/geo'

/** "lat, lon" typed or pasted into the search box, read in the browser; it is never sent anywhere. */
const COORDINATES = /^\s*(-?\d+(\.\d+)?)\s*[,\s]\s*(-?\d+(\.\d+)?)\s*$/

/** Farther than this from the layout origin is not this port. */
export const MAX_DISTANCE_FROM_ORIGIN_M = 20_000

export const OUT_OF_RANGE_MESSAGE = 'Latitude must be -90..90 and longitude -180..180'
export const OUTSIDE_PORT_MESSAGE =
  'That place is outside this port layout. Search is a locator only.'

export type CoordinateQuery =
  | { kind: 'none' }
  | { kind: 'go'; lat: number; lon: number; x: number; y: number; label: string }
  | { kind: 'error'; message: string }

/** What the text means when it is a coordinate pair: a place to go to, an error, or not a coordinate at all. */
export function parseCoordinates(text: string): CoordinateQuery {
  const match = COORDINATES.exec(text)
  if (!match) return { kind: 'none' }
  const lat = Number(match[1])
  const lon = Number(match[3])
  if (Math.abs(lat) > 90 || Math.abs(lon) > 180) {
    return { kind: 'error', message: OUT_OF_RANGE_MESSAGE }
  }
  const [x, y] = fromLonLat(lon, lat)
  if (Math.hypot(x, y) > MAX_DISTANCE_FROM_ORIGIN_M) {
    return { kind: 'error', message: OUTSIDE_PORT_MESSAGE }
  }
  return { kind: 'go', lat, lon, x, y, label: `Go to ${match[1]}, ${match[3]} (lat, lon)` }
}
