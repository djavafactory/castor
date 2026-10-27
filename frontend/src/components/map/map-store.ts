import { create } from 'zustand'
import type { Local } from '@/lib/geo'
import {
  BUTTON_ZOOM,
  GO_TO_MIN_K,
  INITIAL_K,
  KEY_PAN_PX,
  centredOn,
  fitBox,
  panByPixels,
  zoomAround,
  type LocalBox,
  type MapSize,
  type MapView,
} from './map-view'

/** Where the pointer is, in WGS84 and in local metres (the bottom strip shows it). */
export interface Cursor {
  lon: number
  lat: number
  x: number
  y: number
}

/** The orange pin a search drops; it is cleared after {@link PIN_MS}. */
export interface Pin {
  id: number
  x: number
  y: number
}

export const PIN_MS = 4000

interface MapState {
  view: MapView
  size: MapSize
  /** False until the first layout has placed the initial view on the conventional terminal. */
  placed: boolean
  /** Where Reset goes back to: the conventional terminal. */
  home: Local
  cursor: Cursor | null
  pin: Pin | null
  setSize: (size: MapSize) => void
  setView: (view: MapView) => void
  /** Sets the first view once, on the conventional terminal at k 0.5. */
  placeInitial: (centre: Local) => void
  /** Back to the first view. */
  reset: () => void
  panBy: (dxPx: number, dyPx: number) => void
  /** Zooms around a pixel, or the middle of the canvas. */
  zoomBy: (factor: number, around?: [number, number]) => void
  zoomIn: () => void
  zoomOut: () => void
  /** Pans with an arrow key: the ground moves the opposite way to the key. */
  panKey: (dx: -1 | 0 | 1, dy: -1 | 0 | 1) => void
  /** Centres on a local point with a scale of at least `minK`. */
  goTo: (point: Local, minK?: number) => void
  fit: (box: LocalBox) => void
  setCursor: (cursor: Cursor | null) => void
  dropPin: (point: Local) => void
  clearPin: (id: number) => void
}

let pinSeq = 0

const START: MapView = { x: 0, y: 0, k: INITIAL_K }

export const useMapStore = create<MapState>()((set, get) => ({
  view: START,
  size: { width: 1, height: 1 },
  placed: false,
  home: [0, 0],
  cursor: null,
  pin: null,
  setSize: (size) => set({ size }),
  setView: (view) => set({ view }),
  placeInitial: (centre) =>
    set({ view: { x: centre[0], y: centre[1], k: INITIAL_K }, home: centre, placed: true }),
  reset: () => set((s) => ({ view: { x: s.home[0], y: s.home[1], k: INITIAL_K } })),
  panBy: (dx, dy) => set((s) => ({ view: panByPixels(s.view, dx, dy) })),
  zoomBy: (factor, around) =>
    set((s) => {
      const [px, py] = around ?? [s.size.width / 2, s.size.height / 2]
      return { view: zoomAround(s.view, s.size, px, py, factor) }
    }),
  zoomIn: () => get().zoomBy(BUTTON_ZOOM),
  zoomOut: () => get().zoomBy(1 / BUTTON_ZOOM),
  panKey: (dx, dy) => get().panBy(-dx * KEY_PAN_PX, -dy * KEY_PAN_PX),
  goTo: (point, minK = GO_TO_MIN_K) => set((s) => ({ view: centredOn(s.view, point, minK) })),
  fit: (box) => set((s) => ({ view: fitBox(box, s.size) })),
  setCursor: (cursor) => set({ cursor }),
  dropPin: (point) => set({ pin: { id: ++pinSeq, x: point[0], y: point[1] } }),
  // An older timer must not remove a newer pin
  clearPin: (id) => set((s) => (s.pin?.id === id ? { pin: null } : s)),
}))
