import ReactThreeTestRenderer from '@react-three/test-renderer'

// Harness check: scene graphs can be built and inspected without a GPU.
describe('three test renderer', () => {
  it('builds a scene graph and exposes its meshes', async () => {
    const renderer = await ReactThreeTestRenderer.create(
      <group name="yard">
        <mesh name="berth" position={[1, 0, 2]}>
          <boxGeometry args={[2, 1, 4]} />
          <meshBasicMaterial />
        </mesh>
      </group>,
    )

    const berth = renderer.scene.findByProps({ name: 'berth' })
    expect(berth.type).toBe('Mesh')
    expect(berth.instance.position.toArray()).toEqual([1, 0, 2])
    await renderer.unmount()
  })
})
