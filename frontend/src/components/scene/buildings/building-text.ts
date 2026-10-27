import type { ReferenceFeature } from '@/lib/reference-api'

type BuildingProperties = ReferenceFeature['properties']

/** The number of an OpenStreetMap reference: way/1300405460 -> 1300405460. */
export function osmId(osmRef: string): string {
  return osmRef.slice(osmRef.lastIndexOf('/') + 1)
}

/** What the building is called on screen: its mapped name, else its OpenStreetMap id. */
export function buildingTitle(p: Pick<BuildingProperties, 'name' | 'osmRef'>): string {
  return `Building ${p.name ?? osmId(p.osmRef)}`
}

/** Metres or square metres: whole numbers, one decimal only when the value is not whole. */
function figure(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}

/** Hover text: "Building <name or osm id>, <area> m2, <height> m (OpenStreetMap)". */
export function buildingHoverText(p: BuildingProperties): string {
  const parts = [buildingTitle(p)]
  if (p.areaM2 != null) parts.push(`${Math.round(p.areaM2)} m2`)
  if (p.heightM != null) parts.push(`${figure(p.heightM)} m`)
  return `${parts.join(', ')} (OpenStreetMap)`
}
