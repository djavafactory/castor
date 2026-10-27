import type { LayoutFeatureProperties } from '@/lib/api'

/** "Warehouse 3 (WH-3)": hover text of a layout asset. */
export function assetHoverText(p: Pick<LayoutFeatureProperties, 'name' | 'code'>): string {
  return p.name && p.name !== p.code ? `${p.name} (${p.code})` : p.code
}
