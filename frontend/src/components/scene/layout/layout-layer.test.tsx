import ReactThreeTestRenderer from '@react-three/test-renderer'
import type { Mesh } from 'three'
import { currentLayoutSchema } from '@/lib/api'
import { clearActiveFrame, setActiveFrame } from '@/lib/geo'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { currentLayoutFixture } from '@/test/fixtures'
import { CONTAINERS_LAYER } from '../layers'
import { buildPickGeometry } from './pick-geometry'
import { LayoutLayer } from './layout-layer'
import { placeLayout } from './placement'

const FRAME = { lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 }
const features = currentLayoutSchema.parse(currentLayoutFixture).features.features

function pointer(faceIndex: number, delta = 0) {
  return {
    faceIndex,
    delta,
    stopPropagation: () => undefined,
    nativeEvent: { offsetX: 5, offsetY: 6 },
  }
}

beforeEach(() => {
  setActiveFrame(FRAME)
  useSelectionStore.getState().clear()
  useUiStore.setState({ layerVisibility: {}, isolatedLayer: null })
})
afterEach(clearActiveFrame)

describe('LayoutLayer', () => {
  it('draws the layout and the container terminal', async () => {
    const renderer = await ReactThreeTestRenderer.create(<LayoutLayer features={features} />)
    const scene = renderer.scene.instance
    expect(scene.getObjectByName('layout')).toBeDefined()
    expect(scene.getObjectByName('container-yard-CY1')).toBeDefined()
    expect(scene.getObjectByName('asset-visual-STS_CRANE')).toBeDefined()
    expect(scene.getObjectByName('asset-visual-NOT_A_MODEL')).toBeDefined()
    await renderer.unmount()
  })

  it('selects the clicked asset, and ignores a camera drag', async () => {
    const index = placeLayout(features).picks.findIndex((b) => b.assetId === 'wh1')
    const renderer = await ReactThreeTestRenderer.create(<LayoutLayer features={features} />)
    const pick = renderer.scene.findByProps({ name: 'layout-pick' })

    await renderer.fireEvent(pick, 'click', pointer(index * 12 + 3, 12))
    expect(useSelectionStore.getState().selectedAssetId).toBeNull()

    await renderer.fireEvent(pick, 'click', pointer(index * 12 + 3))
    expect(useSelectionStore.getState().selectedAssetId).toBe('wh1')
    await renderer.unmount()
  })

  it('outlines an asset selected elsewhere (the 2D map) on the next render', async () => {
    const renderer = await ReactThreeTestRenderer.create(<LayoutLayer features={features} />)
    expect(renderer.scene.instance.getObjectByName('selection-outline')).toBeUndefined()
    await ReactThreeTestRenderer.act(async () => useSelectionStore.getState().select('wh1'))
    expect(renderer.scene.instance.getObjectByName('selection-outline')).toBeDefined()
    await ReactThreeTestRenderer.act(async () => useSelectionStore.getState().clear())
    expect(renderer.scene.instance.getObjectByName('selection-outline')).toBeUndefined()
    await renderer.unmount()
  })

  it('reports hover with the pointer position and clears it', async () => {
    const onHover = vi.fn()
    const renderer = await ReactThreeTestRenderer.create(
      <LayoutLayer features={features} onHover={onHover} />,
    )
    const pick = renderer.scene.findByProps({ name: 'layout-pick' })
    await renderer.fireEvent(pick, 'pointerMove', pointer(0))
    expect(onHover).toHaveBeenLastCalledWith({
      assetId: placeLayout(features).picks[0]!.assetId,
      x: 5,
      y: 6,
    })
    await renderer.fireEvent(pick, 'pointerOut', pointer(0))
    expect(onHover).toHaveBeenLastCalledWith(null)
    expect(useSelectionStore.getState().hoveredAssetId).toBeNull()
    await renderer.unmount()
  })

  it('cannot hit a yard or crane while the Containers layer is off', async () => {
    useUiStore.setState({ layerVisibility: { [CONTAINERS_LAYER]: false } })
    const renderer = await ReactThreeTestRenderer.create(<LayoutLayer features={features} />)
    expect(renderer.scene.instance.getObjectByName('container-terminal')).toBeUndefined()
    const rest = placeLayout(features).picks.filter(
      (b) => b.assetId !== 'cy1' && b.assetId !== 'sts1',
    )
    const mesh = renderer.scene.findByProps({ name: 'layout-pick' }).instance as Mesh
    expect(mesh.geometry.getAttribute('position').count).toBe(
      buildPickGeometry(rest)!.geometry.getAttribute('position').count,
    )
    await renderer.unmount()
  })

  it('offers no edit handles: only the pick mesh listens, and only for select and hover', async () => {
    const renderer = await ReactThreeTestRenderer.create(<LayoutLayer features={features} />)
    const listening = renderer.scene.findAll((n) =>
      Object.keys(n.props).some((k) => /^on[A-Z]/.test(k)),
    )
    expect(listening.map((n) => n.props.name)).toEqual(['layout-pick'])
    expect(
      Object.keys(listening[0]!.props)
        .filter((k) => /^on[A-Z]/.test(k))
        .sort(),
    ).toEqual(['onClick', 'onPointerMove', 'onPointerOut'])
    await renderer.unmount()
  })
})
