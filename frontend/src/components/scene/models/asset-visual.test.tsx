import ReactThreeTestRenderer from '@react-three/test-renderer'
import { Box3, Vector3, type Mesh, type MeshBasicMaterial, type Object3D } from 'three'
import { AssetVisual } from './asset-visual'
import { COLOUR, RING_OPACITY } from './model-spec'
import { getModelSpec, modelEnvelopeSize } from './registry'

const FORKLIFT = { length: 5, width: 2.2, height: 3 }

function sizeOf(object: Object3D) {
  return new Box3().setFromObject(object).getSize(new Vector3())
}

type Renderer = Awaited<ReturnType<typeof ReactThreeTestRenderer.create>>

function rings(renderer: Renderer) {
  return renderer.scene.findAll((node) => node.props.name === 'uncertainty-ring')
}

describe('AssetVisual', () => {
  it('rescales the model at once when the asset is resized, without rebuilding it', async () => {
    const renderer = await ReactThreeTestRenderer.create(
      <AssetVisual modelKey="FORKLIFT" size={FORKLIFT} />,
    )
    const before = renderer.scene.findByProps({ name: 'model' }).instance
    expect(sizeOf(before).x).toBeCloseTo(5, 3)

    await renderer.update(
      <AssetVisual modelKey="FORKLIFT" size={{ length: 10, width: 3.3, height: 3 }} />,
    )
    const after = renderer.scene.findByProps({ name: 'model' }).instance
    expect(after).toBe(before)
    const size = sizeOf(after)
    expect(size.x).toBeCloseTo(10, 3)
    expect(size.z).toBeCloseTo(3.3, 3)
    expect(size.y).toBeCloseTo(3, 3)
    await renderer.unmount()
  })

  it('draws a model whose envelope is the asset size, A-frame included', async () => {
    const spec = getModelSpec('QUAY_CRANE_RAIL')!
    const size = { length: 16, width: 55, height: 30 }
    const renderer = await ReactThreeTestRenderer.create(
      <AssetVisual modelKey="QUAY_CRANE_RAIL" size={size} />,
    )
    const drawn = sizeOf(renderer.scene.findByProps({ name: 'model' }).instance)
    const envelope = modelEnvelopeSize(spec, size)
    expect(drawn.x).toBeCloseTo(envelope.length, 3)
    expect(drawn.y).toBeCloseTo(envelope.height, 3)
    expect(drawn.z).toBeCloseTo(envelope.width, 3)
    await renderer.unmount()
  })

  it('shows a neutral box at the asset size for an unknown model key', async () => {
    const renderer = await ReactThreeTestRenderer.create(
      <AssetVisual
        modelKey="SPACE_SHUTTLE"
        label="Shuttle 1"
        size={{ length: 8, width: 4, height: 6 }}
      />,
    )
    const box = renderer.scene.findByProps({ name: 'model' }).instance as Mesh
    const size = sizeOf(box)
    expect(size.x).toBeCloseTo(8, 3)
    expect(size.y).toBeCloseTo(6, 3)
    expect(size.z).toBeCloseTo(4, 3)
    expect(new Box3().setFromObject(box).min.y).toBeCloseTo(0, 5)
    expect((box.material as unknown as { color: { getHex: () => number } }).color.getHex()).toBe(
      COLOUR.NEUTRAL,
    )
    await renderer.unmount()
  })

  describe('uncertainty ring', () => {
    it('shows on parked mobile equipment with the uncertainty as radius', async () => {
      const renderer = await ReactThreeTestRenderer.create(
        <AssetVisual modelKey="FORKLIFT" size={FORKLIFT} positionMode="PARKED" uncertaintyM={12} />,
      )
      const found = rings(renderer)
      expect(found).toHaveLength(1)
      const ring = found[0]!.instance as Mesh
      const material = ring.material as MeshBasicMaterial
      expect(material.color.getHex()).toBe(COLOUR.RING)
      expect(material.opacity).toBe(RING_OPACITY)
      ring.geometry.computeBoundingBox()
      expect(ring.geometry.boundingBox!.max.x).toBeCloseTo(12, 3)
      expect(ring.rotation.x).toBeCloseTo(-Math.PI / 2)
      await renderer.unmount()
    })

    it.each([
      ['a tracked machine', 'FORKLIFT', 'TRACKED', 12],
      ['fixed equipment', 'STS_CRANE', 'PARKED', 12],
      ['no uncertainty', 'FORKLIFT', 'PARKED', 0],
    ])('is left out for %s', async (_name, key, mode, uncertainty) => {
      const spec = getModelSpec(key)!
      const renderer = await ReactThreeTestRenderer.create(
        <AssetVisual
          modelKey={key}
          size={spec.size}
          positionMode={mode}
          uncertaintyM={uncertainty}
        />,
      )
      expect(rings(renderer)).toHaveLength(0)
      await renderer.unmount()
    })
  })
})
