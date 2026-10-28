import { describe, expect, it } from 'vitest'
import type { AssetFields } from './asset-fields'
import {
  detailChange,
  formatNumber,
  fromPercent,
  inverseOfChanges,
  parseNumber,
  provenanceChange,
  sameNumber,
  toPercent,
} from './field-values'

const asset = {
  id: 'pad-1',
  name: 'Pad 1',
  rowVersion: 3,
  lengthM: 20,
  provenance: { source: 'OPERATOR', accuracy: 'APPROXIMATE', capturedOn: null, note: null },
  details: {
    storagePad: { bays: 2, rows: 3, bayPitchM: 10, rowPitchM: 6, maxHeightM: 5 },
  },
} as unknown as AssetFields

describe('parseNumber', () => {
  it('reads a number inside the range, spaces ignored', () => {
    expect(parseNumber(' 6.3 ', { label: 'Bay pitch', min: 5 })).toEqual({ ok: true, value: 6.3 })
  })

  it('refuses text and empty input with "enter a number"', () => {
    expect(parseNumber('abc', { label: 'Length' })).toEqual({
      ok: false,
      message: 'Length: enter a number',
    })
    expect(parseNumber('   ', { label: 'Length' })).toEqual({
      ok: false,
      message: 'Length: enter a number',
    })
  })

  it('refuses a decimal below its minimum with the catalogue wording', () => {
    expect(parseNumber('4.9', { label: 'Bay pitch', min: 5 })).toEqual({
      ok: false,
      message: 'Bay pitch: enter a number of at least 5',
    })
  })

  it('refuses a fraction or a value below the minimum in a whole-number field', () => {
    const bays = { label: 'Bays', integer: true, min: 1 }
    expect(parseNumber('2.5', bays)).toEqual({
      ok: false,
      message: 'Bays: enter a whole number of at least 1',
    })
    expect(parseNumber('0', bays)).toEqual({
      ok: false,
      message: 'Bays: enter a whole number of at least 1',
    })
    expect(parseNumber('3', bays)).toEqual({ ok: true, value: 3 })
  })

  it('refuses a value above the maximum', () => {
    expect(parseNumber('101', { label: 'Usable share', min: 1, max: 100 })).toEqual({
      ok: false,
      message: 'Usable share: enter a number of at most 100',
    })
  })
})

describe('formatNumber and percent helpers', () => {
  it('shows digits without trailing zeros and nothing for no value', () => {
    expect(formatNumber(12.5, 3)).toBe('12.5')
    expect(formatNumber(12.3456, 2)).toBe('12.35')
    expect(formatNumber(null, 2)).toBe('')
    expect(formatNumber(undefined, 2)).toBe('')
  })

  it('turns a stored share into percent and back to three decimals', () => {
    expect(toPercent(0.75)).toBe(75)
    expect(toPercent(null)).toBeNull()
    expect(fromPercent(12.5)).toBe(0.125)
    expect(fromPercent(100)).toBe(1)
  })

  it('treats numbers that only differ beyond the stored precision as the same', () => {
    expect(sameNumber(1.5, 1.5 + 1e-12)).toBe(true)
    expect(sameNumber(1.5, 1.501)).toBe(false)
    expect(sameNumber(null, null)).toBe(true)
    expect(sameNumber(null, 0)).toBe(false)
  })
})

describe('detailChange', () => {
  it('sends the whole group with the member changed and keeps the other groups', () => {
    const changes = detailChange(asset, 'storagePad', { bays: 4 })
    expect(changes).toEqual({
      details: { storagePad: { bays: 4, rows: 3, bayPitchM: 10, rowPitchM: 6, maxHeightM: 5 } },
    })
  })

  it('creates a group the asset did not have yet', () => {
    expect(detailChange(asset, 'equipment', { model: 'RS-7' })).toEqual({
      details: {
        storagePad: { bays: 2, rows: 3, bayPitchM: 10, rowPitchM: 6, maxHeightM: 5 },
        equipment: { model: 'RS-7' },
      },
    })
  })
})

describe('inverseOfChanges', () => {
  it('restores the previous detail group, the rest of the groups included', () => {
    const changes = detailChange(asset, 'storagePad', { bays: 4 })
    expect(inverseOfChanges(asset, changes)).toEqual({
      details: { storagePad: { bays: 2, rows: 3, bayPitchM: 10, rowPitchM: 6, maxHeightM: 5 } },
    })
  })

  it('sets a group the asset did not have to null, so the merge removes it again', () => {
    const changes = detailChange(asset, 'equipment', { model: 'RS-7' })
    const back = inverseOfChanges(asset, changes).details as Record<string, unknown>
    expect(back.equipment).toBeNull()
    expect(back.storagePad).toEqual({
      bays: 2,
      rows: 3,
      bayPitchM: 10,
      rowPitchM: 6,
      maxHeightM: 5,
    })
  })

  it('restores the previous provenance and plain values', () => {
    expect(inverseOfChanges(asset, provenanceChange(asset, { note: 'Checked' }))).toEqual({
      provenance: { source: 'OPERATOR', accuracy: 'APPROXIMATE', capturedOn: null, note: null },
    })
    expect(inverseOfChanges(asset, { lengthM: 30 })).toEqual({ lengthM: 20 })
    expect(inverseOfChanges(asset, { operatorName: 'Ops' })).toEqual({ operatorName: null })
  })
})
