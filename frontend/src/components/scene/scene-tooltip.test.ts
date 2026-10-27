import { tooltipPosition } from './scene-tooltip'

const stage = { width: 800, height: 600 }
const box = { width: 200, height: 100 }

describe('tooltipPosition', () => {
  it('sits 16 px from the cursor', () => {
    expect(tooltipPosition({ x: 100, y: 50 }, box, stage)).toEqual({ left: 116, top: 66 })
  })

  it('flips to the other side at the right and bottom edges', () => {
    expect(tooltipPosition({ x: 700, y: 550 }, box, stage)).toEqual({ left: 484, top: 434 })
  })
})
