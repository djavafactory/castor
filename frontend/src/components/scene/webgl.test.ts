import { afterEach, describe, expect, it, vi } from 'vitest'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.resetModules()
})

describe('webglAvailable', () => {
  it('probes once and gives the probe context back, so the palette never uses up the browser contexts', async () => {
    const loseContext = vi.fn()
    const gl = { getExtension: vi.fn(() => ({ loseContext })) }
    vi.stubGlobal('WebGL2RenderingContext', class {})
    const getContext = vi
      .spyOn(HTMLCanvasElement.prototype, 'getContext')
      .mockImplementation(() => gl as unknown as RenderingContext)
    const { webglAvailable } = await import('./webgl')

    for (let i = 0; i < 60; i++) expect(webglAvailable()).toBe(true)

    expect(getContext).toHaveBeenCalledTimes(1)
    expect(gl.getExtension).toHaveBeenCalledWith('WEBGL_lose_context')
    expect(loseContext).toHaveBeenCalledTimes(1)
  })

  it('is false without WebGL', async () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => null)
    vi.stubGlobal('WebGL2RenderingContext', class {})
    const { webglAvailable } = await import('./webgl')
    expect(webglAvailable()).toBe(false)
  })
})
