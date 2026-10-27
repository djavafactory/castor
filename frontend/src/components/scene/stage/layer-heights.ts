/** Fixed heights of the flat layers and their polygon offsets (CORE-03). Nothing else decides where a flat thing sits. */

export interface FlatLayer {
  /** Height above the land plane, metres. */
  y: number
  /** polygonOffset factor = units (GL ignores it under a logarithmic depth buffer; the heights carry the order). */
  offset: number
}

export const LAYERS = {
  satelliteGround: { y: -0.04, offset: 0 },
  land: { y: 0, offset: 0 },
  terminalBoundary: { y: 0.02, offset: -0.3 },
  surfaceArea: { y: 0.03, offset: -0.5 },
  roadSegment: { y: 0.04, offset: -1 },
  osmRoad: { y: 0.045, offset: -1.5 },
  laydownYard: { y: 0.06, offset: -2 },
  bundFloor: { y: 0.06, offset: -2 },
  stagingPad: { y: 0.07, offset: -2 },
  containerYardPad: { y: 0.07, offset: -2 },
  storagePad: { y: 0.08, offset: -2 },
  berthStrip: { y: 0.07, offset: -2.5 },
  internalRoad: { y: 0.07, offset: -2.5 },
  fireAccess: { y: 0.07, offset: -3 },
  junction: { y: 0.07, offset: -3 },
  drainageZone: { y: 0.09, offset: -3 },
  exclusionZone: { y: 0.1, offset: -3 },
  hoverPad: { y: 0.14, offset: -4 },
  ghostPad: { y: 0.08, offset: -4 },
  /** Foam on the water along the quay (a flat layer on the water surface). */
  foam: { y: -1.98, offset: 0 },
  /** Thin marks on roads, and terminal outlines, sit above the 0.04 layers. */
  marking: { y: 0.05, offset: -1.2 },
  /** Marks on the 0.07 layers (fire access, junctions) sit above them. */
  markingTop: { y: 0.075, offset: -3.5 },
} as const satisfies Record<string, FlatLayer>

export type FlatLayerKey = keyof typeof LAYERS

/** Water and its imagery sit below the land plane. */
export const WATER_Y = -2
export const WATER_IMAGERY_Y = -2.04
export const WAVES_Y = -1.99
