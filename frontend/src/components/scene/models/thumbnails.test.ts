import { Box3, Vector3 } from 'three'
import {
  THUMBNAIL_DIRECTION,
  THUMBNAIL_FOV,
  THUMBNAIL_HEIGHT,
  THUMBNAIL_WIDTH,
  renderThumbnail,
  resetThumbnails,
  thumbnailCamera,
  type ThumbnailRenderer,
} from './thumbnails'

function fakeRenderer() {
  return {
    domElement: {
      toDataURL: vi.fn(() => 'data:image/png;base64,AAAA'),
    } as unknown as HTMLCanvasElement,
    setSize: vi.fn(),
    render: vi.fn(),
  } satisfies ThumbnailRenderer
}

beforeEach(resetThumbnails)

describe('thumbnails', () => {
  it('renders 168 x 120 with a 28 degree field of view', () => {
    const renderer = fakeRenderer()
    renderThumbnail('FORKLIFT', renderer)
    expect(renderer.setSize).toHaveBeenCalledWith(168, 120, false)
    expect([THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, THUMBNAIL_FOV]).toEqual([168, 120, 28])
    expect(renderer.render.mock.calls[0]![1].fov).toBe(28)
  })

  it('looks at the middle of the model from the direction (0.9, 0.75, 1.15)', () => {
    const box = new Box3(new Vector3(-10, 0, -5), new Vector3(10, 8, 5))
    const camera = thumbnailCamera(box)
    const centre = box.getCenter(new Vector3())
    const view = camera.position.clone().sub(centre).normalize()
    const wanted = new Vector3(...THUMBNAIL_DIRECTION).normalize()
    expect(view.distanceTo(wanted)).toBeCloseTo(0, 5)
    const ahead = camera.getWorldDirection(new Vector3())
    expect(ahead.dot(centre.clone().sub(camera.position).normalize())).toBeCloseTo(1, 5)
  })

  it('renders each model once and serves the cached image afterwards', () => {
    const renderer = fakeRenderer()
    const first = renderThumbnail('RTG', renderer)
    const second = renderThumbnail('RTG', renderer)
    expect(first).toBe('data:image/png;base64,AAAA')
    expect(second).toBe(first)
    expect(renderer.render).toHaveBeenCalledTimes(1)
  })

  it('has no thumbnail for an unknown model key', () => {
    const renderer = fakeRenderer()
    expect(renderThumbnail('NOPE', renderer)).toBeNull()
    expect(renderer.render).not.toHaveBeenCalled()
  })
})
