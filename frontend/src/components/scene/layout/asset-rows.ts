import type { LayoutFeatureProperties } from '@/lib/api'

export const ACCURACY: Record<string, string> = {
  SURVEYED: 'Surveyed',
  VERIFIED: 'Verified',
  OPERATOR: 'Operator',
  APPROXIMATE: 'Approximate',
}

/** "maxStackM" -> "Max stack M"; the unit letters stay as the server names them. */
export function humanKey(key: string): string {
  const spaced = key.replace(/([a-z0-9])([A-Z])/g, '$1 $2')
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}

function show(value: unknown): string | null {
  if (value === null || value === undefined || value === '') return null
  if (Array.isArray(value)) return value.length > 0 ? value.join(', ') : null
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (typeof value === 'object') return null
  return String(value)
}

/** Label and value rows of the typed details: one row per filled member of each group. */
export function detailRows(details: LayoutFeatureProperties['details']): [string, string][] {
  const rows: [string, string][] = []
  for (const [group, members] of Object.entries(details)) {
    if (!members || typeof members !== 'object') continue
    for (const [key, value] of Object.entries(members)) {
      const text = show(value)
      if (text !== null) rows.push([`${humanKey(group)}: ${humanKey(key)}`, text])
    }
  }
  return rows
}

/** Coordinates are always shown to 7 decimals, as the API sends them. */
export const coord = (v: number): string => v.toFixed(7)

const NUMBER = new Intl.NumberFormat('en-US', { maximumFractionDigits: 1 })
export const figure = (v: number): string => NUMBER.format(v)

/** "47.0 deg true", with " (quay line)" for berths. */
export function headingText(p: LayoutFeatureProperties): string | null {
  if (p.headingDeg == null) return null
  return `${p.headingDeg.toFixed(1)} deg true${p.type === 'BERTH' ? ' (quay line)' : ''}`
}

/** "L x W m, H m high", "N m2 footprint" (polygons, circles) or "L m quay, D m deep" (berths). */
export function sizeText(p: LayoutFeatureProperties, geometryType?: string): string | null {
  if (p.type === 'BERTH') {
    const depth = p.details.berth?.depthM
    const quay = p.lengthM ?? p.computed.lengthM
    if (quay == null) return null
    return `${figure(quay)} m quay${depth == null ? '' : `, ${figure(depth)} m deep`}`
  }
  const area = p.computed.areaM2
  if ((geometryType === 'Polygon' || p.radiusM != null) && area != null) {
    return `${Math.round(area).toLocaleString('en-US')} m2 footprint`
  }
  if (p.lengthM != null && p.widthM != null) {
    return `${figure(p.lengthM)} x ${figure(p.widthM)} m${
      p.heightM == null ? '' : `, ${figure(p.heightM)} m high`
    }`
  }
  return null
}

/** Capacity and design fill (0-100) of the types that store something. */
export function capacityOf(
  p: LayoutFeatureProperties,
): { text: string; fillPct: number | null } | null {
  const s = p.details.storage
  const y = p.details.containerYard
  const pad = p.details.storagePad
  let text: string | null = null
  let fill: number | null | undefined = null
  if (s && typeof s.capacityValue === 'number') {
    text = `${figure(s.capacityValue)} ${String(s.capacityUnit ?? '')}`.trim()
    fill = s.designFill
  } else if (y?.capacityTeu != null) {
    text = `${figure(y.capacityTeu)} TEU`
    fill = y.designFill
  } else if (pad && typeof pad.capacityT === 'number') {
    text = `${figure(pad.capacityT)} t`
  }
  if (text === null) return null
  return { text, fillPct: fill == null ? null : Math.round(fill * 100) }
}

/** Hover tooltip lines: name, type, size, lat/lon, heading (mobile only), accuracy and source. */
export function hoverLines(
  p: LayoutFeatureProperties,
  typeLabel: string,
  geometryType?: string,
): string[] {
  const [lon, lat] = p.anchor
  const lines = [p.name && p.name !== p.code ? `${p.name} (${p.code})` : p.code, typeLabel]
  const size = sizeText(p, geometryType)
  if (size) lines.push(size)
  lines.push(`${coord(lat)}, ${coord(lon)}`)
  if (p.details.mobile && p.headingDeg != null)
    lines.push(`Heading ${p.headingDeg.toFixed(1)} deg true`)
  lines.push(`${ACCURACY[p.provenance.accuracy] ?? p.provenance.accuracy} · ${p.provenance.source}`)
  return lines
}
