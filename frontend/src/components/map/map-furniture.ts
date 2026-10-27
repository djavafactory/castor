/** The scale bar, north arrow and cursor readout of the 2D map: the numbers and texts, not the drawing. */

export const SCALE_STEPS_M = [5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000] as const
export const SCALE_MIN_PX = 80

export interface ScaleBar {
  metres: number
  /** Length of the bar on screen. */
  px: number
  label: string
}

/** The first of 5, 10, 20, 50 ... 5000 m that is at least 80 px long at scale k; "N m" or "N km". */
export function scaleBar(k: number): ScaleBar {
  const metres =
    SCALE_STEPS_M.find((m) => m * k >= SCALE_MIN_PX) ?? SCALE_STEPS_M[SCALE_STEPS_M.length - 1]
  return { metres, px: metres * k, label: metres >= 1000 ? `${metres / 1000} km` : `${metres} m` }
}

/**
 * Degrees the north needle is turned clockwise from straight up. North is (sin r, cos r) in the quay frame, so
 * the needle turns by the frame rotation itself.
 */
export function northNeedleDeg(frameRotDeg: number): number {
  return frameRotDeg
}

/** One decimal that never prints a negative zero. */
function oneDecimal(value: number): string {
  const text = value.toFixed(1)
  return text === '-0.0' ? '0.0' : text
}

export const CURSOR_TOOLTIP = 'Cursor position: WGS84 latitude, longitude and local metres'

/** "<lat 7dp>, <lon 7dp>  (x <x.x>, y <y.y> m)" */
export function cursorReadout(lon: number, lat: number, x: number, y: number): string {
  return `${lat.toFixed(7)}, ${lon.toFixed(7)}  (x ${oneDecimal(x)}, y ${oneDecimal(y)} m)`
}
