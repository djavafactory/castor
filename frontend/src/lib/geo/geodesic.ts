import type { LonLat } from './frame'

// WGS84 ellipsoid
const A = 6378137
const F = 1 / 298.257223563
const B = (1 - F) * A
const RAD = Math.PI / 180

export interface Geodesic {
  /** Distance in metres. */
  distance: number
  /** Initial true bearing at the first point, degrees clockwise from north (0..360). */
  bearing: number
}

/** Vincenty inverse on WGS84. */
export function vincentyInverse(p1: LonLat, p2: LonLat): Geodesic {
  const L = (p2[0] - p1[0]) * RAD
  const U1 = Math.atan((1 - F) * Math.tan(p1[1] * RAD))
  const U2 = Math.atan((1 - F) * Math.tan(p2[1] * RAD))
  const sinU1 = Math.sin(U1)
  const cosU1 = Math.cos(U1)
  const sinU2 = Math.sin(U2)
  const cosU2 = Math.cos(U2)

  let lambda = L
  let sinSigma = 0
  let cosSigma = 1
  let sigma = 0
  let cos2Alpha = 1
  let cos2SigmaM = 0
  let sinLambda = 0
  let cosLambda = 1

  for (let i = 0; i < 200; i += 1) {
    sinLambda = Math.sin(lambda)
    cosLambda = Math.cos(lambda)
    sinSigma = Math.hypot(cosU2 * sinLambda, cosU1 * sinU2 - sinU1 * cosU2 * cosLambda)
    if (sinSigma === 0) return { distance: 0, bearing: 0 }
    cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
    sigma = Math.atan2(sinSigma, cosSigma)
    const sinAlpha = (cosU1 * cosU2 * sinLambda) / sinSigma
    cos2Alpha = 1 - sinAlpha * sinAlpha
    cos2SigmaM = cos2Alpha === 0 ? 0 : cosSigma - (2 * sinU1 * sinU2) / cos2Alpha
    const C = (F / 16) * cos2Alpha * (4 + F * (4 - 3 * cos2Alpha))
    const next =
      L +
      (1 - C) *
        F *
        sinAlpha *
        (sigma + C * sinSigma * (cos2SigmaM + C * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))
    const done = Math.abs(next - lambda) < 1e-12
    lambda = next
    if (done) break
  }

  const uSq = (cos2Alpha * (A * A - B * B)) / (B * B)
  const bigA = 1 + (uSq / 16384) * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
  const bigB = (uSq / 1024) * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))
  const deltaSigma =
    bigB *
    sinSigma *
    (cos2SigmaM +
      (bigB / 4) *
        (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) -
          (bigB / 6) *
            cos2SigmaM *
            (-3 + 4 * sinSigma * sinSigma) *
            (-3 + 4 * cos2SigmaM * cos2SigmaM)))
  const distance = B * bigA * (sigma - deltaSigma)
  const alpha1 = Math.atan2(cosU2 * sinLambda, cosU1 * sinU2 - sinU1 * cosU2 * cosLambda)
  const bearing = (((alpha1 / RAD) % 360) + 360) % 360
  return { distance, bearing }
}

/** Ellipsoidal distance in metres between two WGS84 points (Measure tool). */
export function geodesicDistance(p1: LonLat, p2: LonLat): number {
  return vincentyInverse(p1, p2).distance
}

/** True bearing in degrees clockwise from north (0..360) from p1 towards p2. */
export function trueBearing(p1: LonLat, p2: LonLat): number {
  return vincentyInverse(p1, p2).bearing
}
