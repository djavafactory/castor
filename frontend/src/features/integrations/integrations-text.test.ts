import { describe, expect, it } from 'vitest'
import {
  REASON_CODES,
  REASON_HELP,
  formatWhen,
  isMappingReason,
  publishedText,
  shortId,
  slaText,
  utcInstant,
} from './integrations-text'

describe('integrations text', () => {
  it('writes a time in UTC to the second, and "Never" for none', () => {
    expect(formatWhen('2026-10-29T08:00:01.234Z')).toBe('2026-10-29 08:00:01 UTC')
    expect(formatWhen('2026-10-29T16:00:00+08:00')).toBe('2026-10-29 08:00:00 UTC')
    expect(formatWhen(null)).toBe('Never')
    expect(formatWhen(undefined)).toBe('Never')
    expect(formatWhen('not a date')).toBe('not a date')
  })

  it('shortens a long id and leaves a short one', () => {
    expect(shortId('0198a6f0-0000-7000-8000-000000000001')).toBe('0198a6f0...0001')
    expect(shortId('B9')).toBe('B9')
  })

  it('writes a freshness limit in the largest whole unit', () => {
    expect(slaText(900)).toBe('15 min')
    expect(slaText(7200)).toBe('2 h')
    expect(slaText(45)).toBe('45 s')
    expect(slaText(90)).toBe('90 s')
  })

  it('explains every reason the gateway can give', () => {
    expect(REASON_CODES).toEqual(
      expect.arrayContaining(['UNKNOWN_MAPPING', 'AMBIGUOUS_MAPPING', 'SCHEMA_INVALID']),
    )
    for (const code of REASON_CODES) expect(REASON_HELP[code].length).toBeGreaterThan(10)
  })

  it('offers a mapping only for the reasons a mapping fixes', () => {
    expect(isMappingReason('UNKNOWN_MAPPING')).toBe(true)
    expect(isMappingReason('AMBIGUOUS_MAPPING')).toBe(true)
    expect(isMappingReason('SCHEMA_INVALID')).toBe(false)
    expect(isMappingReason('UNKNOWN_CARGO_MAPPING')).toBe(false)
  })

  it('says how many of a replay events were published', () => {
    expect(publishedText(2, 1)).toBe('1 of 2 published')
    expect(publishedText(0, 0)).toBe('No events')
  })

  it('reads a date-time field as UTC: empty is none, nonsense is null', () => {
    expect(utcInstant('')).toBeUndefined()
    expect(utcInstant('  ')).toBeUndefined()
    expect(utcInstant('2026-10-29T06:00')).toBe('2026-10-29T06:00:00.000Z')
    expect(utcInstant('2026-10-29T06:00:30')).toBe('2026-10-29T06:00:30.000Z')
    expect(utcInstant('yesterday')).toBeNull()
  })
})
