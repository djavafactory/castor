/** The only place 3D colours live (CORE-03). Everything that draws the port takes its colours from here. */

export const palette = {
  canvas: '#F3EFE6',
  paper: '#FFFDF8',
  ink: '#12233F',
  land: '#EAE4D6',
  road: '#C9C2B3',
  pad: '#E3DCCD',
  quay: '#CFC8B6',
  quayWall: '#BDB5A4',
  signal: '#F2622E',
  moss: '#2F8F6B',
  amber: '#E8A317',
  brick: '#C8423B',
  steel: '#2B3442',
  blue: '#3C6FB4',
  white: '#F4F1EA',
  tree: '#6E9B6B',
  treeDark: '#4E7A5A',
  water: { shallow: '#BFDCD6', deep: '#93C3BD', foam: '#EEF8F5' },
  surface: { concrete: '#D9D3C6', asphalt: '#B9B2A3', gravel: '#CFC3A8', grass: '#C9D6B0' },
  osmBuilding: '#9AA3AD',
  warehouseWall: '#C9BFA3',
  /** Roofs are the wall colour this much lighter. */
  roofLighten: 0.35,
  containers: [
    '#B5463A',
    '#2F6DB5',
    '#E8A317',
    '#4E7A5A',
    '#D9D3C6',
    '#6B7480',
    '#C9672E',
    '#1E3A66',
  ],
  /** Flat-layer colours named by the ticket's road and terminal specifications. */
  osmRoad: '#8F8F89',
  internalRoad: '#B9B2A3',
  fireAccessFill: '#D9A29B',
  terminalT1: '#E3D9BC',
  terminalT6: '#D6D4CC',
  /** Accuracy classes of a shape (SETUP-03): outline colours and the legend swatches. */
  accuracy: {
    SURVEYED: '#2F8F6B',
    VERIFIED: '#3C6FB4',
    OPERATOR: '#E8A317',
    APPROXIMATE: '#8A8F98',
  },
  /** The 2D map (DESIGNER-02): grid lines and the thin outlines of the OpenStreetMap background. */
  map: { grid: '#D3CCBB', osmOutline: '#9A9EA6' },
  /** Hover label and selection. */
  select: {
    shells: ['#F2622E', '#FF8A5C', '#FFB08F'],
    designFill: '#5BA8F5',
    designEdge: '#2F7FD6',
  },
  /** The 3D editing gizmo (JOIN-01): one colour per part. */
  gizmo: { length: '#E5483D', width: '#2B6FE0', height: '#2FB26B', rotate: '#F2A33A' },
  light: { sky: '#FFF8EA', ground: '#C9BFA8', sun: '#FFF4E0' },
  tankMetalness: 0.2,
  /** Labels: pill background and border are rgba strings. */
  label: { fill: 'rgba(255,253,248,.97)', border: 'rgba(18,35,63,.22)' },
} as const

/** '#RRGGBB' -> 0xRRGGBB, for three.js. */
export function hex(colour: string): number {
  return parseInt(colour.slice(1), 16)
}

/** A colour moved towards white by the given share, computed on the sRGB channels as designers do. */
export function lighten(colour: number, share: number): number {
  const channel = (shift: number) => {
    const c = (colour >> shift) & 0xff
    return Math.round(c + (255 - c) * share)
  }
  return (channel(16) << 16) | (channel(8) << 8) | channel(0)
}

/** Every colour string of the palette, flattened (used by the lint test that keeps them out of other files). */
export function paletteHexValues(): string[] {
  const out = new Set<string>()
  const walk = (v: unknown) => {
    if (typeof v === 'string' && /^#[0-9A-Fa-f]{6}$/.test(v)) out.add(v.toUpperCase())
    else if (Array.isArray(v)) v.forEach(walk)
    else if (v && typeof v === 'object') Object.values(v).forEach(walk)
  }
  walk(palette)
  return [...out]
}
