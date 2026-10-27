import { describe, expect, it } from 'vitest'
import {
  GO_TO_MIN_K,
  MAX_K,
  MIN_K,
  centredOn,
  clampK,
  fitBox,
  localToScreen,
  panByPixels,
  screenToLocal,
  tileMatrix,
  viewBox,
  wheelFactor,
  zoomAround,
  type MapSize,
  type MapView,
} from './map-view'

const size: MapSize = { width: 1000, height: 600 }
const view: MapView = { x: 120, y: 340, k: 0.5 }

describe('the view transform', () => {
  it('puts the view centre in the middle of the canvas, with y up', () => {
    expect(localToScreen(view, size, 120, 340)).toEqual([500, 300])
    // 100 m to the right and 100 m inland: 50 px right, 50 px up
    expect(localToScreen(view, size, 220, 440)).toEqual([550, 250])
  })

  it('converts back and forth without loss', () => {
    const [px, py] = localToScreen(view, size, -7.9, 346.9)
    const [x, y] = screenToLocal(view, size, px, py)
    expect(x).toBeCloseTo(-7.9, 9)
    expect(y).toBeCloseTo(346.9, 9)
  })

  it('shows the local box of the canvas', () => {
    expect(viewBox(view, size)).toEqual({ x0: -880, x1: 1120, y0: -260, y1: 940 })
  })
})

describe('zoom', () => {
  it('wheel zoom is exp(-deltaY x 0.0015)', () => {
    expect(wheelFactor(0)).toBe(1)
    expect(wheelFactor(-100)).toBeCloseTo(Math.exp(0.15), 12)
    expect(wheelFactor(100)).toBeCloseTo(Math.exp(-0.15), 12)
  })

  it('keeps the point under the cursor where it is', () => {
    const [x, y] = screenToLocal(view, size, 800, 120)
    const zoomed = zoomAround(view, size, 800, 120, 2.5)
    expect(zoomed.k).toBeCloseTo(1.25, 12)
    const [px, py] = localToScreen(zoomed, size, x, y)
    expect(px).toBeCloseTo(800, 9)
    expect(py).toBeCloseTo(120, 9)
  })

  it('stays between 0.02 and 20 px per metre', () => {
    expect(clampK(0.001)).toBe(MIN_K)
    expect(clampK(500)).toBe(MAX_K)
    expect(zoomAround(view, size, 500, 300, 1e6).k).toBe(MAX_K)
    expect(zoomAround(view, size, 500, 300, 1e-6).k).toBe(MIN_K)
  })
})

describe('pan', () => {
  it('moves the ground with the pointer', () => {
    const [px, py] = localToScreen(view, size, 0, 0)
    const panned = panByPixels(view, 30, -20)
    const [qx, qy] = localToScreen(panned, size, 0, 0)
    expect(qx).toBeCloseTo(px + 30, 9)
    expect(qy).toBeCloseTo(py - 20, 9)
    expect(panned.k).toBe(view.k)
  })
})

describe('going to a place', () => {
  it('centres on the point and never leaves the map zoomed out past the minimum', () => {
    expect(centredOn({ x: 0, y: 0, k: 0.1 }, [10, 20], GO_TO_MIN_K)).toEqual({
      x: 10,
      y: 20,
      k: GO_TO_MIN_K,
    })
    expect(centredOn({ x: 0, y: 0, k: 3 }, [10, 20], GO_TO_MIN_K).k).toBe(3)
  })

  it('fits a box with air around it', () => {
    const fitted = fitBox({ x0: 0, x1: 400, y0: 0, y1: 100 }, size, 50)
    expect(fitted.x).toBe(200)
    expect(fitted.y).toBe(50)
    expect(fitted.k).toBeCloseTo(900 / 400, 12)
    const box = viewBox(fitted, size)
    expect(box.x0).toBeLessThanOrEqual(0)
    expect(box.x1).toBeGreaterThanOrEqual(400)
  })

  it('zooms to a point as far as the maximum scale for a box of no size', () => {
    expect(fitBox({ x0: 5, x1: 5, y0: 5, y1: 5 }, size).k).toBe(4)
  })
})

describe('tile matrix', () => {
  it('maps the pixels of an unrotated tile to screen pixels at k = 1', () => {
    // a 256 m tile whose north-west corner is at the view centre, south-west 256 m below it
    const m = tileMatrix([0, 0], [256, 0], [0, -256], { x: 0, y: 0, k: 1 }, size, 1)
    expect(m).toEqual([1, -0, 0, 1, 500, 300])
  })

  it('carries the rotation of the frame and the device pixel ratio', () => {
    // north-east corner straight up: the tile is turned a quarter turn
    const m = tileMatrix([0, 0], [0, 256], [256, 0], { x: 0, y: 0, k: 1 }, size, 2)
    expect(m[0]).toBeCloseTo(0, 12)
    expect(m[1]).toBeCloseTo(-2, 12)
    expect(m[2]).toBeCloseTo(2, 12)
    expect(m[3]).toBeCloseTo(0, 12)
    expect([m[4], m[5]]).toEqual([1000, 600])
  })
})
