import { afterEach, describe, expect, it } from 'vitest'
import {
  BASEMAPS,
  DEFAULT_BASEMAP,
  GOOGLE_NOTE,
  GOOGLE_TOAST,
  basemapLabel,
  gridStepFor,
  isBasemap,
  loadBasemap,
  mayTraceOn,
  saveBasemap,
  tileZoomFor,
} from './basemap'

afterEach(() => localStorage.clear())

describe('basemap options', () => {
  it('offers the three basemaps with the labels of the ticket, Sentinel-2 first', () => {
    expect(BASEMAPS.map((b) => b.label)).toEqual([
      'Sentinel-2 (EOX)',
      'None (grid)',
      'Google Maps (lookup only)',
    ])
    expect(DEFAULT_BASEMAP).toBe('sentinel2')
    expect(basemapLabel('google')).toBe('Google Maps (lookup only)')
  })

  it('says why Google cannot be traced', () => {
    expect(GOOGLE_NOTE).toBe(
      'Google Maps is a locator only: its imagery cannot be traced into stored geometry.',
    )
    expect(GOOGLE_TOAST).toBe(
      'Google Maps (lookup / reference only): tracing into stored geometry is not allowed. Switch the basemap first.',
    )
    expect([mayTraceOn('sentinel2'), mayTraceOn('none'), mayTraceOn('google')]).toEqual([
      true,
      true,
      false,
    ])
  })

  it('knows its own values', () => {
    expect(isBasemap('none')).toBe(true)
    expect(isBasemap('osm')).toBe(false)
    expect(isBasemap(null)).toBe(false)
  })
})

describe('tile zoom', () => {
  it('is round(log2(156543.03 x cos(lat) x k)) clamped to 8-14', () => {
    // 156543.03 x cos(2.95 deg) x 0.5 = 78,169 -> log2 = 16.25 -> clamped to 14
    expect(tileZoomFor(2.95, 0.5)).toBe(14)
    // k 0.02: 3,127 -> log2 = 11.6 -> 12
    expect(tileZoomFor(2.95, 0.02)).toBe(12)
    // k 0.1: 15,634 -> 13.93 -> 14; k 0.05: 7,817 -> 12.93 -> 13
    expect(tileZoomFor(2.95, 0.1)).toBe(14)
    expect(tileZoomFor(2.95, 0.05)).toBe(13)
    // the floor is 8
    expect(tileZoomFor(80, 0.0001)).toBe(8)
  })
})

describe('grid step', () => {
  it('is the first of 10, 25, 50, 100, 250, 500, 1000 m that is at least 40 px', () => {
    expect(gridStepFor(20)).toBe(10)
    expect(gridStepFor(4)).toBe(10)
    expect(gridStepFor(3.9)).toBe(25)
    expect(gridStepFor(0.5)).toBe(100)
    expect(gridStepFor(0.16)).toBe(250)
    expect(gridStepFor(0.08)).toBe(500)
    expect(gridStepFor(0.04)).toBe(1000)
  })

  it('uses the widest spacing when even that is closer than 40 px', () => {
    expect(gridStepFor(0.02)).toBe(1000)
  })
})

describe('the preference of a user', () => {
  it('is remembered for that user only', () => {
    saveBasemap('ada', 'none')
    expect(loadBasemap('ada')).toBe('none')
    expect(loadBasemap('grace')).toBe(DEFAULT_BASEMAP)
  })

  it('is the default when nobody is signed in or the stored value is not a basemap', () => {
    saveBasemap(null, 'google')
    expect(localStorage.length).toBe(0)
    expect(loadBasemap(null)).toBe(DEFAULT_BASEMAP)
    localStorage.setItem('castor.basemap.ada', 'tampered')
    expect(loadBasemap('ada')).toBe(DEFAULT_BASEMAP)
  })
})
