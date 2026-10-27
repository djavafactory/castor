import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { hex, lighten, palette, paletteHexValues } from './palette'

describe('palette', () => {
  it('holds the colours of the 3D style', () => {
    expect(palette.canvas).toBe('#F3EFE6')
    expect(palette.signal).toBe('#F2622E')
    expect(palette.water).toEqual({ shallow: '#BFDCD6', deep: '#93C3BD', foam: '#EEF8F5' })
    expect(palette.surface).toEqual({
      concrete: '#D9D3C6',
      asphalt: '#B9B2A3',
      gravel: '#CFC3A8',
      grass: '#C9D6B0',
    })
    expect(palette.containers).toHaveLength(8)
    expect(palette.osmBuilding).toBe('#9AA3AD')
    expect(palette.roofLighten).toBe(0.35)
  })

  it('converts to three.js numbers and lightens towards white', () => {
    expect(hex('#F2622E')).toBe(0xf2622e)
    expect(lighten(0x000000, 0.35)).toBe(0x595959)
    expect(lighten(0xffffff, 0.35)).toBe(0xffffff)
  })

  it('lists every colour once', () => {
    const all = paletteHexValues()
    expect(new Set(all).size).toBe(all.length)
    expect(all).toContain('#C8423B')
  })
})

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return sources(path)
    return /\.(ts|tsx)$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : []
  })
}

describe('palette values live only in palette.ts', () => {
  it('no other 3D source repeats a palette colour', () => {
    const values = paletteHexValues().map((v) => v.slice(1).toLowerCase())
    const root = join(__dirname, '..', '..')
    const offenders: string[] = []
    for (const file of sources(join(root, 'components', 'scene'))) {
      const text = readFileSync(file, 'utf8').toLowerCase()
      for (const v of values) {
        if (text.includes(`#${v}`) || text.includes(`0x${v}`)) {
          offenders.push(`${relative(root, file).split(sep).join('/')}: ${v}`)
        }
      }
    }
    expect(offenders).toEqual([])
  })
})
