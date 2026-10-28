import type { AssetFields } from './asset-fields'

/** What a numeric field accepts: its label for the message, whole numbers only, and the range. */
export interface NumberSpec {
  label: string
  integer?: boolean
  min?: number
  max?: number
}

export type Parsed = { ok: true; value: number } | { ok: false; message: string }

/**
 * Reads what the editor typed. Invalid input is refused with the wording of the Inspector: "<Label>: enter a number",
 * "<Label>: enter a number of at least <min>" (decimal fields) or "<Label>: enter a whole number of at least <min>"
 * (whole-number fields). Nothing is stored for an invalid value.
 */
export function parseNumber(text: string, spec: NumberSpec): Parsed {
  const trimmed = text.trim()
  const value = trimmed === '' ? Number.NaN : Number(trimmed)
  if (!Number.isFinite(value)) return { ok: false, message: `${spec.label}: enter a number` }
  const whole = spec.integer === true
  if (whole && !Number.isInteger(value)) {
    return { ok: false, message: wholeMessage(spec) }
  }
  if (spec.min !== undefined && value < spec.min) {
    return {
      ok: false,
      message: whole ? wholeMessage(spec) : `${spec.label}: enter a number of at least ${spec.min}`,
    }
  }
  if (spec.max !== undefined && value > spec.max) {
    return {
      ok: false,
      message: whole
        ? `${spec.label}: enter a whole number of at most ${spec.max}`
        : `${spec.label}: enter a number of at most ${spec.max}`,
    }
  }
  return { ok: true, value }
}

function wholeMessage(spec: NumberSpec): string {
  return spec.min === undefined
    ? `${spec.label}: enter a whole number`
    : `${spec.label}: enter a whole number of at least ${spec.min}`
}

/** The text a numeric field shows: plain digits, rounded to the given decimals, no trailing zeros. */
export function formatNumber(value: number | null | undefined, decimals: number): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return ''
  return String(Number(value.toFixed(decimals)))
}

/**
 * Changes a PATCH carries, with the values they replace. A detail group or the provenance is sent whole (the server
 * merges objects, the editor replaces them at once), so the inverse is the previous group; a group that did not exist
 * is set to null, which the merge removes again. Lists and geometries are replaced as they are.
 */
export function inverseOfChanges(
  asset: AssetFields,
  changes: Record<string, unknown>,
): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const key of Object.keys(changes)) {
    if (key === 'details') {
      const previous = { ...(asset.details as Record<string, unknown>) }
      for (const group of Object.keys(changes.details as Record<string, unknown>)) {
        previous[group] = (asset.details as Record<string, unknown>)[group] ?? null
      }
      out.details = previous
    } else if (key === 'provenance') {
      out.provenance = { ...asset.provenance }
    } else {
      out[key] = (asset as unknown as Record<string, unknown>)[key] ?? null
    }
  }
  return out
}

/** A PATCH that changes members of one detail group, keeping the other groups and any change already made. */
export function detailChange(
  asset: AssetFields,
  group: string,
  members: Record<string, unknown>,
  base?: Record<string, unknown>,
): { details: Record<string, unknown> } {
  const details = (base?.details ?? asset.details ?? {}) as Record<string, unknown>
  const current = (details[group] ?? {}) as Record<string, unknown>
  return { details: { ...details, [group]: { ...current, ...members } } }
}

/** A PATCH that changes members of the provenance. */
export function provenanceChange(asset: AssetFields, members: Record<string, unknown>) {
  return { provenance: { ...asset.provenance, ...members } }
}

/** Same number up to the precision the server keeps (3 decimals, 7 for coordinates). */
export const sameNumber = (a: number | null | undefined, b: number | null | undefined): boolean =>
  a === null || a === undefined || b === null || b === undefined ? a === b : Math.abs(a - b) < 1e-9

/** The fractions the server stores as a share of one, shown as percent. */
export const toPercent = (fraction: number | null | undefined) =>
  fraction === null || fraction === undefined ? null : Math.round(fraction * 100 * 10) / 10

export const fromPercent = (percent: number) => Math.round(percent * 10) / 1000
