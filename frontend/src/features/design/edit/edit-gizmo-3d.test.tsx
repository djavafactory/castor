import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { RootState } from '@react-three/fiber'
import ReactThreeTestRenderer from '@react-three/test-renderer'
import { act } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Vector3 } from 'three'
import { layerOf } from '@/components/scene/layers'
import { assetTypeSchema } from '@/lib/api'
import { fromLonLat, headingToRotation, rectangle, setActiveFrame, type Local } from '@/lib/geo'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { assetTypesFixture, layoutFeature, portFixture } from '@/test/fixtures'
import { server } from '@/test/setup'
import { storedLonLat } from './edit-model'
import { useEditPreview } from './edit-preview-store'
import { EditGizmo3D, gizmoSize } from './edit-gizmo-3d'

vi.mock('sonner', () => ({ toast: vi.fn() }))

const BASE = 'http://api.test/api/v1'
const NO_LIMITS = { lengthM: null, widthM: null, heightM: null, radiusM: null, lineWidthM: null }
const SHED = assetTypeSchema.parse({
  ...assetTypesFixture[0],
  code: 'SHED',
  label: 'Shed',
  category: 'STORAGE',
  geometryKind: 'RECT',
  flags: { ...assetTypesFixture[0]!.flags, mobile: false, solid: true },
  min: NO_LIMITS,
  max: { ...NO_LIMITS, lengthM: 70 },
})

const SHED_AT: Local = [200, 100]
const HEIGHT = 10

function draftWire() {
  const ring = rectangle(storedLonLat(SHED_AT), 0, 50, 20).map((p) => [...p])
  const shed = layoutFeature(
    'shed-1',
    'SHED',
    {
      name: 'Shed 1',
      anchor: [...storedLonLat(SHED_AT)],
      headingDeg: 0,
      lengthM: 50,
      widthM: 20,
      heightM: HEIGHT,
      rowVersion: 3,
    },
    { type: 'Polygon', coordinates: [[...ring, ring[0]!]] },
  )
  return {
    layout: {
      id: 'draft-7',
      versionNo: 7,
      status: 'DRAFT',
      basedOnVersionNo: 1,
      createdBy: 'ada',
      createdAt: '2026-10-27T01:00:00Z',
      updatedBy: 'grace',
      updatedAt: '2026-10-27T02:00:00Z',
    },
    features: { type: 'FeatureCollection', features: [shed] },
  }
}

let patched: Record<string, unknown>[] = []
let three: RootState

/** Puts the camera above the shed, looking down at it at an angle, and keeps the R3F state for the test. */
function stage(state: RootState) {
  three = state
  state.camera.position.set(SHED_AT[0], 160, -SHED_AT[1] + 120)
  state.camera.lookAt(SHED_AT[0], 0, -SHED_AT[1])
  state.camera.updateMatrixWorld()
  const { width, height } = state.size
  state.gl.domElement.getBoundingClientRect = () =>
    ({ left: 0, top: 0, width, height, right: width, bottom: height, x: 0, y: 0 }) as DOMRect
}

/** Stands in for the layout layer's pick mesh: a box over the shed that says which asset it is. */
function PickBox() {
  return (
    <mesh
      name="layout-pick"
      position={[SHED_AT[0], HEIGHT / 2, -SHED_AT[1]]}
      userData={{ assetAt: () => 'shed-1' }}
    >
      <boxGeometry args={[50, HEIGHT, 20]} />
    </mesh>
  )
}

async function renderGizmo() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  server.use(
    http.get(`${BASE}/ports/WESTPORTS`, () => HttpResponse.json(portFixture)),
    http.get(`${BASE}/ports/WESTPORTS/layouts/draft`, () => HttpResponse.json(draftWire())),
    http.get(`${BASE}/asset-types`, () => HttpResponse.json([SHED])),
    http.patch(`${BASE}/layouts/draft-7/assets/:id`, async ({ request, params }) => {
      const body = (await request.json()) as Record<string, unknown>
      patched.push(body)
      return HttpResponse.json({
        feature: {
          type: 'Feature',
          id: params.id,
          geometry: { type: 'Polygon', coordinates: [] },
          properties: { type: 'SHED', rowVersion: 4, ...body },
        },
        warnings: [],
      })
    }),
  )
  const renderer = await ReactThreeTestRenderer.create(
    <QueryClientProvider client={client}>
      <PickBox />
      <EditGizmo3D frameReady />
    </QueryClientProvider>,
    { onCreated: stage },
  )
  // The port, the types and the draft arrive and are parsed
  for (let i = 0; i < 20 && !renderer.scene.instance.getObjectByName('gizmo'); i++) {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 20))
    })
  }
  await renderer.advanceFrames(1, 16)
  // The test renderer does not draw, so nothing else brings the world matrices up to date
  renderer.scene.instance.updateMatrixWorld(true)
  return renderer
}

/** A scene point on the screen, in client pixels. */
function screenOf(p: Vector3) {
  const v = p.clone().project(three.camera)
  return { x: ((v.x + 1) / 2) * three.size.width, y: ((1 - v.y) / 2) * three.size.height }
}

function fire(type: string, at: Vector3, init: PointerEventInit = {}) {
  const { x, y } = screenOf(at)
  const event = new PointerEvent(type, {
    clientX: x,
    clientY: y,
    button: 0,
    pointerId: 1,
    bubbles: true,
    cancelable: true,
    ...init,
  })
  const target = type === 'pointerdown' ? three.gl.domElement : window
  act(() => {
    target.dispatchEvent(event)
  })
}

function drag(from: Vector3, to: Vector3, init: PointerEventInit = {}) {
  fire('pointerdown', from, init)
  fire('pointermove', to, init)
  fire('pointerup', to, init)
}

/** The point on an own axis of the shed's gizmo (+length, +width or up), a share of the gizmo size from its centre. */
function onArrow(axis: 'length' | 'width' | 'height', share = 0.8) {
  const top = new Vector3(SHED_AT[0], HEIGHT, -SHED_AT[1])
  const size = gizmoSize(three.camera.position.distanceTo(top))
  const r = (headingToRotation(0) * Math.PI) / 180 + (axis === 'width' ? Math.PI / 2 : 0)
  const dir = axis === 'height' ? new Vector3(0, 1, 0) : new Vector3(Math.cos(r), 0, -Math.sin(r))
  return { point: top.clone().addScaledVector(dir, size * share), dir }
}

const history = () => useEditorStore.getState().undoStack.map((e) => e.label)
const anchorOf = (body: Record<string, unknown>) => {
  const a = body.anchor as [number, number]
  return fromLonLat(a[0], a[1])
}

beforeEach(() => {
  setActiveFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 })
  patched = []
  vi.mocked(toast).mockClear()
  useSelectionStore.getState().clear()
  useSelectionStore.getState().select('shed-1')
  useEditorStore.getState().reset()
  useEditPreview.getState().setPreview(null)
  useUiStore.setState({ mode: 'design', lockedLayers: {}, viewMode: '3d' })
})

describe('EditGizmo3D', () => {
  it('shows the selection box and the four parts of a rectangle', async () => {
    const renderer = await renderGizmo()
    const scene = renderer.scene.instance
    expect(scene.getObjectByName('selection-design')).toBeDefined()
    for (const part of ['length', 'width', 'height', 'rotate']) {
      expect(scene.getObjectByName(`gizmo-${part}`)).toBeDefined()
    }
    // The hit meshes are not drawn and not hit by the scene's raycaster
    expect(scene.getObjectByName('gizmo-hit-length')!.layers.isEnabled(0)).toBe(false)
    await renderer.unmount()
  })

  it('shows no gizmo while a tool is active or the layer is locked', async () => {
    useEditorStore.setState({ tool: { kind: 'draw', typeCode: 'SHED', points: [] } } as never)
    const renderer = await renderGizmo()
    expect(renderer.scene.instance.getObjectByName('gizmo')).toBeUndefined()
    await renderer.unmount()

    useEditorStore.setState({ tool: null })
    const layer = layerOf('SHED', 'STORAGE')!
    useUiStore.setState({ lockedLayers: { [layer]: true } })
    const locked = await renderGizmo()
    expect(locked.scene.instance.getObjectByName('gizmo')).toBeUndefined()
    await locked.unmount()
  })

  it('drags the red arrow: the far edge follows, the near edge stays, one PATCH and one undo step', async () => {
    const renderer = await renderGizmo()
    const { point, dir } = onArrow('length')
    drag(point, point.clone().addScaledVector(dir, 10))
    await vi.waitFor(() => expect(patched).toHaveLength(1))
    expect(patched[0]!.lengthM).toBeCloseTo(60, 1)
    expect(patched[0]!.widthM).toBe(20)
    // The anchor moves half the change: the back edge stays where it was
    const [x, y] = anchorOf(patched[0]!)
    const r = (headingToRotation(0) * Math.PI) / 180
    expect(x).toBeCloseTo(SHED_AT[0] + 5 * Math.cos(r), 0)
    expect(y).toBeCloseTo(SHED_AT[1] + 5 * Math.sin(r), 0)
    await vi.waitFor(() => expect(history()).toEqual(['Changed the length of Shed 1']))
    await renderer.unmount()
  })

  it('drags the green arrow to change the height', async () => {
    const renderer = await renderGizmo()
    const { point } = onArrow('height')
    drag(point, point.clone().add(new Vector3(0, 4, 0)))
    await vi.waitFor(() => expect(patched).toHaveLength(1))
    expect(patched[0]!.heightM).toBeCloseTo(HEIGHT + 4, 0)
    await vi.waitFor(() => expect(history()).toEqual(['Changed the height of Shed 1']))
    await renderer.unmount()
  })

  it('turns the ring to rotate, in whole degrees', async () => {
    const renderer = await renderGizmo()
    const top = new Vector3(SHED_AT[0], HEIGHT, -SHED_AT[1])
    const size = gizmoSize(three.camera.position.distanceTo(top))
    const at = (deg: number) =>
      top
        .clone()
        .add(
          new Vector3(
            Math.cos((deg * Math.PI) / 180),
            0,
            -Math.sin((deg * Math.PI) / 180),
          ).multiplyScalar(size * 0.8),
        )
    fire('pointerdown', at(-90))
    fire('pointermove', at(-80))
    fire('pointermove', at(-60))
    fire('pointerup', at(-60))
    await vi.waitFor(() => expect(patched).toHaveLength(1))
    const heading = patched[0]!.headingDeg as number
    expect(Number.isInteger(heading)).toBe(true)
    expect(heading).toBe(330)
    await vi.waitFor(() => expect(history()).toEqual(['Rotated Shed 1']))
    await renderer.unmount()
  })

  it('moves an asset pressed on its body, on a horizontal plane', async () => {
    useSelectionStore.getState().clear()
    const renderer = await renderGizmo()
    // The side of the box faces the camera; press low on it so the gizmo is not in the way
    const from = new Vector3(SHED_AT[0] - 20, HEIGHT, -SHED_AT[1] + 8)
    fire('pointerdown', from)
    expect(useSelectionStore.getState().selectedAssetId).toBe('shed-1')
    fire('pointermove', from.clone().add(new Vector3(15, 0, -6)))
    expect(useEditPreview.getState().preview).toMatchObject({ assetId: 'shed-1', view: '3d' })
    fire('pointerup', from.clone().add(new Vector3(15, 0, -6)))
    await vi.waitFor(() => expect(patched).toHaveLength(1))
    const [x, y] = anchorOf(patched[0]!)
    expect(x).toBeCloseTo(SHED_AT[0] + 15, 0)
    expect(y).toBeCloseTo(SHED_AT[1] + 6, 0)
    await vi.waitFor(() => expect(history()).toEqual(['Moved Shed 1']))
    await renderer.unmount()
  })

  it('refuses an edit the rules reject: red box while dragging, toast and no PATCH on release', async () => {
    const renderer = await renderGizmo()
    const { point, dir } = onArrow('length')
    const to = point.clone().addScaledVector(dir, 30)
    fire('pointerdown', point)
    fire('pointermove', to)
    expect(useEditPreview.getState().preview).toMatchObject({ valid: false })
    fire('pointerup', to)
    expect(toast).toHaveBeenCalledWith(expect.stringMatching(/^Not changed: /))
    expect(patched).toHaveLength(0)
    expect(useEditPreview.getState().preview).toBeNull()
    await renderer.unmount()
  })

  it('updates the preview at most every 70 ms, and the 2D map reads the same shape', async () => {
    const renderer = await renderGizmo()
    const { point, dir } = onArrow('length')
    fire('pointerdown', point)
    fire('pointermove', point.clone().addScaledVector(dir, 4))
    const first = useEditPreview.getState().preview
    expect(first?.shape).toMatchObject({ kind: 'rect', length: expect.closeTo(54, 1) })
    fire('pointermove', point.clone().addScaledVector(dir, 8))
    // Still the first one: the next update waits for the throttle
    expect(useEditPreview.getState().preview).toBe(first)
    await vi.waitFor(() =>
      expect(useEditPreview.getState().preview?.shape).toMatchObject({
        length: expect.closeTo(58, 1),
      }),
    )
    fire('pointerup', point.clone().addScaledVector(dir, 8))
    await vi.waitFor(() => expect(patched).toHaveLength(1))
    await renderer.unmount()
  })

  it('leaves a press on empty ground to the camera', async () => {
    const renderer = await renderGizmo()
    const ground = new Vector3(SHED_AT[0] + 200, 0, -SHED_AT[1] - 100)
    const event = new PointerEvent('pointerdown', {
      ...screenOf(ground),
      clientX: screenOf(ground).x,
      clientY: screenOf(ground).y,
      button: 0,
      pointerId: 1,
      bubbles: true,
      cancelable: true,
    })
    const reached = vi.fn()
    three.gl.domElement.addEventListener('pointerdown', reached)
    act(() => {
      three.gl.domElement.dispatchEvent(event)
    })
    expect(reached).toHaveBeenCalled()
    expect(event.defaultPrevented).toBe(false)
    await renderer.unmount()
  })
})
