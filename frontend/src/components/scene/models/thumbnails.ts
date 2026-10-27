import {
  AmbientLight,
  Box3,
  DirectionalLight,
  PerspectiveCamera,
  Scene,
  Sphere,
  Vector3,
  WebGLRenderer,
} from 'three'
import { buildModel, disposeModel, getModelSpec } from './registry'

/** Rendered size, pixels; the palette shows the image at half of it (84 x 60). */
export const THUMBNAIL_WIDTH = 168
export const THUMBNAIL_HEIGHT = 120
export const THUMBNAIL_FOV = 28
/** Direction from the model to the camera. */
export const THUMBNAIL_DIRECTION: [number, number, number] = [0.9, 0.75, 1.15]

/** What the thumbnail needs of a renderer; a WebGLRenderer fits. */
export interface ThumbnailRenderer {
  domElement: HTMLCanvasElement
  setSize: (width: number, height: number, updateStyle?: boolean) => void
  render: (scene: Scene, camera: PerspectiveCamera) => void
}

/** Camera that looks at the middle of the bounds along the thumbnail direction, from just far enough. */
export function thumbnailCamera(bounds: Box3): PerspectiveCamera {
  const sphere = bounds.getBoundingSphere(new Sphere())
  const camera = new PerspectiveCamera(
    THUMBNAIL_FOV,
    THUMBNAIL_WIDTH / THUMBNAIL_HEIGHT,
    0.1,
    10000,
  )
  // The model has to fit the narrower of the two fields of view: the vertical one at this aspect.
  const distance = (sphere.radius / Math.sin((THUMBNAIL_FOV * Math.PI) / 360)) * 1.05
  camera.position
    .copy(sphere.center)
    .addScaledVector(new Vector3(...THUMBNAIL_DIRECTION).normalize(), distance)
  camera.lookAt(sphere.center)
  camera.updateMatrixWorld()
  return camera
}

const cache = new Map<string, string>()
let renderer: ThumbnailRenderer | null = null

function sharedRenderer(): ThumbnailRenderer {
  if (!renderer) {
    // One renderer for every thumbnail: browsers allow only a handful of WebGL contexts.
    const canvas = document.createElement('canvas')
    const gl = new WebGLRenderer({
      canvas,
      antialias: true,
      alpha: true,
      preserveDrawingBuffer: true,
    })
    gl.setPixelRatio(1)
    gl.setClearColor(0x000000, 0)
    renderer = gl
  }
  return renderer
}

/**
 * Renders a model once, off-screen, and keeps the image: later calls return the cached data URL.
 * Returns null for an unknown key.
 */
export function renderThumbnail(
  key: string,
  using: ThumbnailRenderer = sharedRenderer(),
): string | null {
  const cached = cache.get(key)
  if (cached) return cached
  const spec = getModelSpec(key)
  if (!spec) return null

  const { group } = buildModel(spec)
  const scene = new Scene()
  scene.add(new AmbientLight(0xffffff, 1.1))
  const sun = new DirectionalLight(0xffffff, 2.2)
  sun.position.set(0.6, 1.2, 0.4)
  scene.add(sun, group)

  using.setSize(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, false)
  using.render(scene, thumbnailCamera(new Box3().setFromObject(group)))
  const image = using.domElement.toDataURL('image/png')

  disposeModel(group)
  cache.set(key, image)
  return image
}

/** Forgets the cached images and the renderer (tests; a lost WebGL context). */
export function resetThumbnails() {
  cache.clear()
  if (renderer && 'dispose' in renderer) (renderer as WebGLRenderer).dispose()
  renderer = null
}
