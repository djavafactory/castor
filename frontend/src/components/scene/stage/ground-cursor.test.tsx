import ReactThreeTestRenderer from '@react-three/test-renderer'
import { Vector3 } from 'three'
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { useMapStore } from '@/components/map/map-store'
import { clearActiveFrame, fromLonLat, setActiveFrame } from '@/lib/geo'
import { useEditorStore } from '@/lib/stores/editor-store'
import { GroundCursor } from './ground-cursor'

beforeAll(() => setActiveFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 }))
afterAll(() => clearActiveFrame())

beforeEach(() => {
  useEditorStore.getState().reset()
  useMapStore.setState({ cursor: null })
})

describe('3D ground cursor', () => {
  it('is not there while no tool is active, so it costs nothing and catches nothing', async () => {
    const renderer = await ReactThreeTestRenderer.create(<GroundCursor />)
    expect(renderer.scene.findAllByProps({ name: 'ground-cursor' })).toHaveLength(0)
    await renderer.unmount()
  })

  it('feeds the cursor readout with the pointer on the ground while placing', async () => {
    useEditorStore.getState().setTool('place', 'EXCAVATOR')
    const renderer = await ReactThreeTestRenderer.create(<GroundCursor />)
    const plane = renderer.scene.findByProps({ name: 'ground-cursor' })

    // scene X is local x, scene Z is local -y: scene (-7.9, 0, -346.9) is local (-7.9, 346.9), the main gate
    await renderer.fireEvent(plane, 'pointerMove', { point: new Vector3(-7.9, 0, -346.9) })

    const cursor = useMapStore.getState().cursor
    expect(cursor).not.toBeNull()
    expect(cursor!.x).toBeCloseTo(-7.9, 9)
    expect(cursor!.y).toBeCloseTo(346.9, 9)
    const [x, y] = fromLonLat(cursor!.lon, cursor!.lat)
    expect(x).toBeCloseTo(-7.9, 6)
    expect(y).toBeCloseTo(346.9, 6)
    await renderer.unmount()
  })

  it('clears the readout when the pointer leaves the ground', async () => {
    useEditorStore.getState().setTool('measure')
    const renderer = await ReactThreeTestRenderer.create(<GroundCursor />)
    const plane = renderer.scene.findByProps({ name: 'ground-cursor' })
    await renderer.fireEvent(plane, 'pointerMove', { point: new Vector3(1, 0, -2) })
    expect(useMapStore.getState().cursor).not.toBeNull()

    await renderer.fireEvent(plane, 'pointerOut')

    expect(useMapStore.getState().cursor).toBeNull()
    await renderer.unmount()
  })
})
