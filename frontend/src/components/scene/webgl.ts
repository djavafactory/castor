let available: boolean | null = null

/**
 * WebGL is checked up front so a browser without it gets a message instead of a blank stage. Checked once: every
 * probe is a WebGL context, and the browser keeps only about 16 before it drops the oldest (the 3D stage, the
 * palette thumbnails), so the probe context is also given back straight away.
 */
export function webglAvailable(): boolean {
  if (available !== null) return available
  if (
    typeof WebGL2RenderingContext === 'undefined' &&
    typeof WebGLRenderingContext === 'undefined'
  ) {
    available = false
    return available
  }
  try {
    const canvas = document.createElement('canvas')
    const gl = canvas.getContext('webgl2') ?? canvas.getContext('webgl')
    available = Boolean(gl)
    gl?.getExtension('WEBGL_lose_context')?.loseContext()
  } catch {
    available = false
  }
  return available
}
