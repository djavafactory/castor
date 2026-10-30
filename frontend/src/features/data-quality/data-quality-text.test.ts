import { describe, expect, it } from 'vitest'
import { ageText, causeText, detailText, entityText } from './data-quality-text'

const NOW = Date.parse('2026-10-30T10:00:00Z')

describe('ageText', () => {
  it('counts seconds, minutes, hours and days and never goes negative', () => {
    expect(ageText('2026-10-30T09:59:15Z', NOW)).toBe('45 s')
    expect(ageText('2026-10-30T09:48:00Z', NOW)).toBe('12 min')
    expect(ageText('2026-10-30T06:55:00Z', NOW)).toBe('3 h 5 min')
    expect(ageText('2026-10-30T08:00:00Z', NOW)).toBe('2 h')
    expect(ageText('2026-10-28T09:00:00Z', NOW)).toBe('2 d')
    expect(ageText('2026-10-30T10:05:00Z', NOW)).toBe('0 s')
  })

  it('says unknown when there is no time', () => {
    expect(ageText(null, NOW)).toBe('Unknown')
    expect(ageText('soon', NOW)).toBe('Unknown')
  })
})

describe('entityText', () => {
  it('shortens ids and the two ids of an inventory', () => {
    expect(entityText('equipment', '0198a6f0-0000-7000-8000-000000000001')).toBe(
      'equipment 0198a6f0...0001',
    )
    expect(entityText('inventory', '0198a6f0-0000-7000-8000-000000000001:COIL')).toBe(
      'inventory 0198a6f0...0001:COIL',
    )
  })
})

describe('detailText', () => {
  it('describes a source conflict on a vessel call field', () => {
    expect(
      detailText({
        kind: 'SOURCE_CONFLICT',
        detail: {
          field: 'ata',
          applied: { source: 'edic', value: '2026-10-30T08:50:00Z' },
          other: { source: 'cbas', value: '2026-10-30T08:20:00Z' },
          differenceMinutes: 30,
        },
      }),
    ).toBe(
      'ata: edic 2026-10-30T08:50:00Z applied, cbas 2026-10-30T08:20:00Z kept in the history (30 min apart)',
    )
  })

  it('describes a level that differs from the book', () => {
    expect(
      detailText({
        kind: 'SOURCE_CONFLICT',
        detail: {
          field: 'level',
          commodity: 'OIL',
          observed: 1030,
          book: 1000,
          unit: 'CUBIC_METRES',
          differencePercent: 3,
        },
      }),
    ).toBe('OIL: measured 1030 against book 1000 CUBIC_METRES (3 %)')
  })

  it('describes a negative balance, a capacity breach and a stale event', () => {
    expect(
      detailText({
        kind: 'NEGATIVE_BALANCE',
        detail: { commodity: 'COIL', balance: 10, unit: 'PIECES', delta: -15 },
      }),
    ).toBe('COIL: balance 10 PIECES, a movement of -15 was recorded but not applied')
    expect(
      detailText({
        kind: 'CAPACITY_EXCEEDED',
        detail: { commodity: 'COAL', stock: 120, unit: 'TONNES', capacity: 100 },
      }),
    ).toBe('COAL: stock 120 TONNES above the usable capacity 100')
    expect(
      detailText({
        kind: 'STALE_EVENT',
        detail: {
          type: 'equipment.position.observed',
          source: 'SENSORS',
          occurredAt: 'a',
          currentSource: 'SENSORS',
          currentOccurredAt: 'b',
        },
      }),
    ).toBe(
      'equipment.position.observed from SENSORS at a is older than the state held (SENSORS, b)',
    )
  })
})

describe('causeText', () => {
  it('drops the reason the error starts with', () => {
    expect(causeText({ reason: 'UNKNOWN_ENTITY', error: 'UNKNOWN_ENTITY: No cargo lot X' })).toBe(
      'No cargo lot X',
    )
    expect(causeText({ reason: 'A', error: 'something else' })).toBe('something else')
  })
})
