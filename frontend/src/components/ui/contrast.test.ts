/// <reference types="node" />
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const css = readFileSync(path.resolve(import.meta.dirname, '../../index.css'), 'utf8')

function token(name: string): string {
  const match = new RegExp(`--${name}:\\s*(#[0-9a-f]{6})`, 'i').exec(css)
  if (!match) throw new Error(`token --${name} not found`)
  return match[1]
}

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

describe('colour tokens meet WCAG AA', () => {
  it.each([
    ['ink', 'paper', 4.5],
    ['muted', 'paper', 4.5],
    ['paper', 'ink', 4.5],
    ['bad', 'paper', 4.5],
    // White on signal is 3.2:1: AA for large or bold text only (3:1). Flagged in the SETUP-06 review notes.
    ['on-signal', 'signal', 3],
  ])('%s on %s is at least %s:1', (fg, bg, min) => {
    expect(contrast(token(fg as string), token(bg as string))).toBeGreaterThanOrEqual(min as number)
  })
})
