import ReactThreeTestRenderer from '@react-three/test-renderer'
import type { Mesh } from 'three'
import { clearActiveFrame, setActiveFrame } from '@/lib/geo'
import type { ReferenceFeature } from '@/lib/reference-api'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { BuildingsLayer } from './buildings-layer'
import { buildBuildingsGeometry } from './building-geometry'

const FRAME = { lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 }

function building(osmRef: string, lon: number, size = 0.0002): ReferenceFeature {
  const lat = 2.9534
  return {
    type: 'Feature',
    id: osmRef,
    geometry: {
      type: 'Polygon',
      coordinates: [
        [
          [lon, lat],
          [lon + size, lat],
          [lon + size, lat + size],
          [lon, lat + size],
          [lon, lat],
        ],
      ],
    },
    properties: {
      kind: 'BUILDING',
      osmRef,
      name: null,
      heightM: 12,
      areaM2: 400,
      source: 'OSM',
      accuracy: 'APPROXIMATE',
    },
  }
}

const BUILDINGS = [
  building('way/1', 101.3066),
  building('way/2', 101.3076),
  building('way/3', 101.3086),
]

function pointerEvent(faceIndex: number, delta = 0) {
  return {
    faceIndex,
    delta,
    stopPropagation: () => undefined,
    nativeEvent: { offsetX: 120, offsetY: 80 },
  }
}

beforeEach(() => {
  setActiveFrame(FRAME)
  useSelectionStore.getState().clear()
})
afterEach(clearActiveFrame)

describe('BuildingsLayer', () => {
  it('renders every building as one mesh, so one draw call', async () => {
    const renderer = await ReactThreeTestRenderer.create(<BuildingsLayer buildings={BUILDINGS} />)

    const meshes = renderer.scene.findAllByType('Mesh')
    expect(meshes).toHaveLength(1)
    const mesh = meshes[0]!.instance as Mesh
    expect(mesh.geometry.getAttribute('position').count).toBeGreaterThan(3 * 3 * 10)
    await renderer.unmount()
  })

  it('selects the clicked building and no other', async () => {
    const built = buildBuildingsGeometry(BUILDINGS)
    const renderer = await ReactThreeTestRenderer.create(<BuildingsLayer buildings={BUILDINGS} />)
    const mesh = renderer.scene.findByType('Mesh')

    for (const [index, wanted] of ['way/1', 'way/2', 'way/3'].entries()) {
      const face = built.faceToFeature.findIndex((f) => f === index)
      await renderer.fireEvent(mesh, 'click', pointerEvent(face))
      expect(useSelectionStore.getState().selectedReferenceId).toBe(wanted)
    }
    await renderer.unmount()
  })

  it('ignores a click that was a camera drag', async () => {
    const renderer = await ReactThreeTestRenderer.create(<BuildingsLayer buildings={BUILDINGS} />)
    await renderer.fireEvent(renderer.scene.findByType('Mesh'), 'click', pointerEvent(0, 12))
    expect(useSelectionStore.getState().selectedReferenceId).toBeNull()
    await renderer.unmount()
  })

  it('reports hover with the pointer position and clears it when the pointer leaves', async () => {
    const onHover = vi.fn()
    const renderer = await ReactThreeTestRenderer.create(
      <BuildingsLayer buildings={BUILDINGS} onHover={onHover} />,
    )
    const mesh = renderer.scene.findByType('Mesh')

    await renderer.fireEvent(mesh, 'pointerMove', pointerEvent(0))
    expect(onHover).toHaveBeenLastCalledWith({ osmRef: 'way/1', x: 120, y: 80 })
    expect(useSelectionStore.getState().hoveredReferenceId).toBe('way/1')

    await renderer.fireEvent(mesh, 'pointerOut', pointerEvent(0))
    expect(onHover).toHaveBeenLastCalledWith(null)
    expect(useSelectionStore.getState().hoveredReferenceId).toBeNull()
    await renderer.unmount()
  })

  it('hands the built geometry to the caller so the camera can frame it', async () => {
    const onBuilt = vi.fn()
    const renderer = await ReactThreeTestRenderer.create(
      <BuildingsLayer buildings={BUILDINGS} onBuilt={onBuilt} />,
    )
    expect(onBuilt).toHaveBeenCalledTimes(1)
    expect(onBuilt.mock.calls[0]![0].features).toHaveLength(3)
    await renderer.unmount()
  })
})
