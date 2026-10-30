import type { Freshness } from '@/lib/api'

/** UTC to the second: unambiguous for operators in any time zone, and the same in every test. */
export function formatWhen(iso: string | null | undefined): string {
  if (!iso) return 'Never'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return `${date.toISOString().slice(0, 19).replace('T', ' ')} UTC`
}

/** An id of 36 characters, shortened for a table cell (the full id is in the title and the detail). */
export function shortId(id: string): string {
  return id.length > 13 ? `${id.slice(0, 8)}...${id.slice(-4)}` : id
}

export const FRESHNESS_LABEL: Record<Freshness, string> = {
  FRESH: 'Fresh',
  STALE: 'Stale',
  UNKNOWN: 'Never seen',
}

/** "15 min", "2 h", "45 s": the SLA a source has to meet. */
export function slaText(seconds: number): string {
  if (seconds % 3600 === 0 && seconds >= 3600) return `${seconds / 3600} h`
  if (seconds % 60 === 0 && seconds >= 60) return `${seconds / 60} min`
  return `${seconds} s`
}

/** What each quarantine reason means for the person looking at it. */
export const REASON_HELP: Record<string, string> = {
  SCHEMA_INVALID: 'The record is not a valid event: a field is missing or has the wrong form.',
  PORT_MISMATCH: 'The record names another port than the one it was sent to.',
  NO_ADAPTER: 'No adapter reads this source yet.',
  ADAPTER_ERROR: 'The adapter failed on this record. The log has the error id in the detail.',
  UNKNOWN_MAPPING:
    'The source named an asset or call that has no mapping. Add the mapping, then replay.',
  AMBIGUOUS_MAPPING:
    'More than one mapping applies to the source id at that time. Close one, then replay.',
  MALFORMED_UNIT: 'A quantity has no unit, an unknown unit, or one that does not fit the cargo.',
  UNKNOWN_CARGO_MAPPING: 'The source used a cargo family or commodity code that has no mapping.',
}

export const REASON_CODES = Object.keys(REASON_HELP)

/** The reasons an identity mapping fixes: the quarantine drawer offers "Add mapping" for these. */
export function isMappingReason(code: string): boolean {
  return code === 'UNKNOWN_MAPPING' || code === 'AMBIGUOUS_MAPPING'
}

/** `2 of 3 published`, or `none` for a replay that produced no event. */
export function publishedText(events: number, published: number): string {
  return events === 0 ? 'No events' : `${published} of ${events} published`
}

/**
 * The instant of a `datetime-local` input, read as UTC (the form says so), or undefined when it is empty.
 * Returns null for text that is not a date.
 */
export function utcInstant(local: string): string | undefined | null {
  if (local.trim() === '') return undefined
  const date = new Date(`${local}${local.length === 16 ? ':00' : ''}Z`)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}
