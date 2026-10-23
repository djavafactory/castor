/** Quay frame of one port: the origin and the rotation of local +X, as served by GET /api/v1/ports/{code}. */
export interface QuayFrameParams {
  /** Origin longitude, degrees (WGS84). */
  lon0: number
  /** Origin latitude, degrees (WGS84). */
  lat0: number
  /** Angle of local +X, degrees counter-clockwise from east. */
  rotDeg: number
}

export type LonLat = [lon: number, lat: number]
/** Local quay-frame metres [x, y]. */
export type Local = [x: number, y: number]

const RAD = Math.PI / 180

/** Metres per degree at a latitude: [mLat, mLon] (SETUP-03 formulas, identical to the backend QuayFrame). */
export function metresPerDegree(latDeg: number): [mLat: number, mLon: number] {
  const mLat = 111132.92 - 559.82 * Math.cos(2 * latDeg * RAD) + 1.175 * Math.cos(4 * latDeg * RAD)
  const mLon = 111412.84 * Math.cos(latDeg * RAD) - 93.5 * Math.cos(3 * latDeg * RAD)
  return [mLat, mLon]
}

/** Normalises an angle to the range [0, 360). */
function mod360(deg: number): number {
  const r = deg % 360
  return r < 0 ? r + 360 : r
}

/** Normalises an angle to the range (-180, 180]. */
function wrap180(deg: number): number {
  const r = mod360(deg)
  return r > 180 ? r - 360 : r
}

/** Conversions bound to one quay frame. Create it from the port record, never from constants. */
export function createFrame({ lon0, lat0, rotDeg }: QuayFrameParams) {
  const [mLat, mLon] = metresPerDegree(lat0)
  const c = Math.cos(rotDeg * RAD)
  const s = Math.sin(rotDeg * RAD)

  return {
    params: { lon0, lat0, rotDeg } as QuayFrameParams,

    /** Local metres -> WGS84 [lon, lat]. */
    toLonLat(x: number, y: number): LonLat {
      return [lon0 + (x * c - y * s) / mLon, lat0 + (x * s + y * c) / mLat]
    },

    /** WGS84 -> local metres [x, y]. */
    fromLonLat(lon: number, lat: number): Local {
      const e = (lon - lon0) * mLon
      const n = (lat - lat0) * mLat
      return [e * c + n * s, -e * s + n * c]
    },

    /** Heading (degrees true, clockwise from north) -> rotation (degrees ccw from local +X, -180..180). */
    headingToRotation(headingDeg: number): number {
      return wrap180(90 - headingDeg - rotDeg)
    },

    /** Rotation (degrees ccw from local +X) -> heading (degrees true, 0..360). */
    rotationToHeading(rot: number): number {
      return mod360(90 - rot - rotDeg)
    },
  }
}

export type QuayFrame = ReturnType<typeof createFrame>
