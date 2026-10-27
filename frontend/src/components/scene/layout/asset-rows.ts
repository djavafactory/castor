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
