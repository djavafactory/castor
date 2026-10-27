import { describe, expect, it } from 'vitest'
import {
  BAY_PITCH,
  BOX_HEIGHT,
  BOX_LENGTH,
  PAD_TOP,
  bayCount,
  generateBoxes,
  hashStr,
  mulberry32,
  rowOffsets,
  teuOf,
  type ContainerBox,
  type YardSpec,
} from './container-stacks'

const YARD: YardSpec = { code: 'CY-1', length: 300, width: 120, maxTiers: 5, designFill: 0.6 }

describe('hash and random', () => {
  it('hashes with FNV-1a 32 bit', () => {
    expect(hashStr('')).toBe(0x811c9dc5)
    expect(hashStr('a')).toBe(0xe40c292c)
    expect(hashStr('foobar')).toBe(0xbf9cf968)
  })

  it('draws the same numbers from a seed, all in [0, 1)', () => {
    const a = mulberry32(hashStr('CY-1'))
    const b = mulberry32(hashStr('CY-1'))
    for (let i = 0; i < 1000; i++) {
      const value = a()
      expect(value).toBe(b())
      expect(value).toBeGreaterThanOrEqual(0)
      expect(value).toBeLessThan(1)
    }
  })
})

describe('layout', () => {
  it('fits bays on a 6.3 m pitch inside a 2 m margin', () => {
    expect(bayCount(300)).toBe(Math.floor(298 / 6.3))
    expect(bayCount(1)).toBe(0)
  })

  it('puts an aisle of 2.4 m after every 7th row and keeps rows inside width - 4 m', () => {
    const rows = rowOffsets(120)
    expect(rows[1] - rows[0]).toBeCloseTo(2.7)
    expect(rows[7] - rows[6]).toBeCloseTo(2.7 + 2.4)
    expect(rows.at(-1)! + 2.7).toBeLessThanOrEqual(116)
    expect(rows.at(-1)! + 2.7 + 2.7).toBeGreaterThan(116) // one more row would not fit
    expect(rowOffsets(5)).toEqual([])
  })
})

describe('generateBoxes', () => {
  it('gives the same boxes on every call, and other boxes for another code', () => {
    expect(generateBoxes(YARD)).toEqual(generateBoxes(YARD))
    expect(generateBoxes({ ...YARD, code: 'CY-2' })).not.toEqual(generateBoxes(YARD))
  })

  it('is empty for a yard too small for a box, or without tiers', () => {
    expect(generateBoxes({ ...YARD, length: 5 })).toEqual([])
    expect(generateBoxes({ ...YARD, maxTiers: 0 })).toEqual([])
  })

  it('stacks every box exactly on the one below, with the same length', () => {
    const boxes = generateBoxes(YARD)
    const cells = new Map<string, ContainerBox[]>()
    for (const box of boxes) {
      const key = `${box.row}/${box.bay}`
      cells.set(key, [...(cells.get(key) ?? []), box])
    }
    expect(cells.size).toBeGreaterThan(100)
    for (const stack of cells.values()) {
      stack.forEach((box, i) => {
        expect(box.tier).toBe(i + 1)
        const bottom = box.y - BOX_HEIGHT / 2
        const below = stack[i - 1]
        if (below) {
          expect(Math.abs(bottom - (below.y + BOX_HEIGHT / 2))).toBeLessThan(0.001)
          expect(box.size).toBe(below.size)
          expect([box.x, box.z]).toEqual([below.x, below.z])
        } else {
          expect(bottom).toBeCloseTo(PAD_TOP, 9)
        }
        expect(box.y).toBeCloseTo(PAD_TOP + i * BOX_HEIGHT + BOX_HEIGHT / 2, 9)
      })
    }
  })

  it('never exceeds the maximum tiers and never leaves the pad', () => {
    for (const maxTiers of [1, 3, 6]) {
      const yard = { ...YARD, maxTiers }
      for (const box of generateBoxes(yard)) {
        expect(box.tier).toBeGreaterThanOrEqual(1)
        expect(box.tier).toBeLessThanOrEqual(maxTiers)
        const half = BOX_LENGTH[box.size] / 2
        expect(Math.abs(box.x) + half).toBeLessThanOrEqual(yard.length / 2)
        expect(Math.abs(box.z) + 1.3).toBeLessThanOrEqual(yard.width / 2)
      }
    }
  })

  it('never puts two boxes in one place, nor overlaps a 40 ft box with its neighbour', () => {
    const boxes = generateBoxes(YARD)
    const places = new Set(boxes.map((b) => `${b.row}/${b.bay}/${b.tier}`))
    expect(places.size).toBe(boxes.length)
    const spans = new Map<number, [number, number][]>()
    for (const b of boxes.filter((box) => box.tier === 1)) {
      const half = BOX_LENGTH[b.size] / 2
      spans.set(b.row, [...(spans.get(b.row) ?? []), [b.x - half, b.x + half]])
    }
    for (const row of spans.values()) {
      row.sort((a, b) => a[0] - b[0])
      row.forEach((span, i) => {
        if (i > 0) expect(span[0]).toBeGreaterThanOrEqual(row[i - 1][1] - 1e-9)
      })
    }
  })

  it('puts a 40 ft box across two bays and never in the last bay', () => {
    const boxes = generateBoxes(YARD)
    const fortyFeet = boxes.filter((b) => b.size === 40)
    expect(fortyFeet.length).toBeGreaterThan(0)
    expect(boxes.some((b) => b.size === 20)).toBe(true)
    expect(Math.max(...fortyFeet.map((b) => b.bay))).toBeLessThan(bayCount(YARD.length) - 1)
    const [first] = fortyFeet
    expect(first.x).toBeCloseTo(-YARD.length / 2 + 1 + (first.bay + 1) * BAY_PITCH, 9)
  })

  it('follows the design fill', () => {
    const count = (designFill: number) => generateBoxes({ ...YARD, designFill }).length
    expect(count(0)).toBeLessThan(count(0.5))
    expect(count(0.5)).toBeLessThan(count(1))
  })

  it('keeps colour keys in 0..7 and brightness in 0.9..1.08', () => {
    for (const box of generateBoxes(YARD)) {
      expect(Number.isInteger(box.colourKey)).toBe(true)
      expect(box.colourKey).toBeGreaterThanOrEqual(0)
      expect(box.colourKey).toBeLessThan(8)
      expect(box.brightness).toBeGreaterThanOrEqual(0.9)
      expect(box.brightness).toBeLessThan(1.08)
    }
  })

  it('makes more than 50,000 boxes quickly', () => {
    const big = { code: 'BIG', length: 1200, width: 400, maxTiers: 6, designFill: 0.9 }
    const start = performance.now()
    const boxes = generateBoxes(big)
    expect(boxes.length).toBeGreaterThan(50_000)
    expect(performance.now() - start).toBeLessThan(2000)
  })

  it('counts a 40 ft box as two TEU', () => {
    const boxes = generateBoxes(YARD)
    const forty = boxes.filter((b) => b.size === 40).length
    expect(teuOf(boxes)).toBe(boxes.length + forty)
  })
})
