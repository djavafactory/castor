/** Texts of the Design bottom strip. */

export function crsTip(measureSrid: number): string {
  return `Stored as WGS84 (EPSG:4326, GeoJSON lon/lat order). Lengths and areas are computed in metres (EPSG:${measureSrid}).`
}
export const ACCURACY_TIP = 'Colour outlines by accuracy label; dashed = approximate'
export const ENVELOPE_TIP =
  "Draw each asset's bounding box (footprint x height) in 3D to catch wrong scale"

const count = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`

/** "N errors, M warnings" text of the issues button. */
export function issuesText(errors: number, warnings: number): string {
  return `${count(errors, 'error')}, ${count(warnings, 'warning')}`
}
