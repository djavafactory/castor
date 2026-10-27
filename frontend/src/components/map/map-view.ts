import type { Local } from '@/lib/geo'

/**
 * The 2D map is drawn in the quay frame: +x along the quay to the right, +y inland upwards, one unit a metre. A view is
 * the local point at the middle of the canvas and the scale k in pixels per metre. Nothing here touches the DOM.
 */
export interface MapView {
  /** Local x (metres) at the middle of the canvas. */
  x: number
  /** Local y (metres) at the middle of the canvas. */
  y: number
  /** Pixels per metre. */
  k: number
}

export interface MapSize {
  width: number
  height: number
}

export interface LocalBox {
  x0: number
  x1: number
  y0: number
  y1: number
}

export const MIN_K = 0.02
export const MAX_K = 20
export const INITIAL_K = 0.5
/** Zoom of a wheel step: exp(-deltaY x 0.0015). */
export const WHEEL_SENSITIVITY = 0.0015
/** The +/- buttons and keys zoom by this much, like the 3D camera. */
export const BUTTON_ZOOM = 1.35
/** Arrow keys pan by this many pixels. */
export const KEY_PAN_PX = 80
/** Going to a place never leaves the map zoomed out further than this. */
export const GO_TO_MIN_K = 0.6

export function clampK(k: number): number {
  return Math.min(MAX_K, Math.max(MIN_K, k))
}

/** The zoom factor of one wheel event. */
export function wheelFactor(deltaY: number): number {
  return Math.exp(-deltaY * WHEEL_SENSITIVITY)
}

export function screenToLocal(view: MapView, size: MapSize, px: number, py: number): Local {
  return [view.x + (px - size.width / 2) / view.k, view.y - (py - size.height / 2) / view.k]
}

export function localToScreen(
  view: MapView,
  size: MapSize,
  x: number,
  y: number,
): [number, number] {
  return [size.width / 2 + (x - view.x) * view.k, size.height / 2 - (y - view.y) * view.k]
}

/** The local rectangle the canvas shows. */
export function viewBox(view: MapView, size: MapSize): LocalBox {
  const [x0, y1] = screenToLocal(view, size, 0, 0)
  const [x1, y0] = screenToLocal(view, size, size.width, size.height)
  return { x0, x1, y0, y1 }
}

/** Zooms by `factor` keeping the point under pixel (px, py) where it is. */
export function zoomAround(
  view: MapView,
  size: MapSize,
  px: number,
  py: number,
  factor: number,
): MapView {
  const k = clampK(view.k * factor)
  const [x, y] = screenToLocal(view, size, px, py)
  return {
    k,
    x: x - (px - size.width / 2) / k,
    y: y + (py - size.height / 2) / k,
  }
}

/** Drags the map by (dx, dy) pixels: the ground follows the pointer. */
export function panByPixels(view: MapView, dx: number, dy: number): MapView {
  return { ...view, x: view.x - dx / view.k, y: view.y + dy / view.k }
}

/** Centres on a local point, zoomed in to at least `minK`. */
export function centredOn(view: MapView, point: Local, minK = 0): MapView {
  return { x: point[0], y: point[1], k: clampK(Math.max(view.k, minK)) }
}

/** The view that shows a local box with some air around it; a box of no size gets `maxK`. */
export function fitBox(box: LocalBox, size: MapSize, padPx = 48, maxK = 4): MapView {
  const w = box.x1 - box.x0
  const h = box.y1 - box.y0
  const room = {
    width: Math.max(1, size.width - 2 * padPx),
    height: Math.max(1, size.height - 2 * padPx),
  }
  const k = clampK(Math.min(w > 0 ? room.width / w : maxK, h > 0 ? room.height / h : maxK, maxK))
  return { x: (box.x0 + box.x1) / 2, y: (box.y0 + box.y1) / 2, k }
}

/** The tile transform of a 256 px image whose corners sit at local nw, ne and sw, as the six numbers of setTransform. */
export function tileMatrix(
  nw: Local,
  ne: Local,
  sw: Local,
  view: MapView,
  size: MapSize,
  dpr: number,
  tilePx = 256,
): [number, number, number, number, number, number] {
  const s = (dpr * view.k) / tilePx
  return [
    s * (ne[0] - nw[0]),
    -s * (ne[1] - nw[1]),
    s * (sw[0] - nw[0]),
    -s * (sw[1] - nw[1]),
    dpr * (size.width / 2 + (nw[0] - view.x) * view.k),
    dpr * (size.height / 2 - (nw[1] - view.y) * view.k),
  ]
}
