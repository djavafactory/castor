/** WebGL is checked up front so a browser without it gets a message instead of a blank stage. */
export function webglAvailable(): boolean {
  if (
    typeof WebGL2RenderingContext === 'undefined' &&
    typeof WebGLRenderingContext === 'undefined'
  ) {
    return false
  }
  try {
    const canvas = document.createElement('canvas')
    return Boolean(canvas.getContext('webgl2') ?? canvas.getContext('webgl'))
  } catch {
    return false
  }
}
