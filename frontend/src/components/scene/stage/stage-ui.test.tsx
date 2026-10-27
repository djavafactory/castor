import { palette } from '@/features/scene/palette'
import type { Mesh } from 'three'
import ReactThreeTestRenderer from '@react-three/test-renderer'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HelpPopover } from '@/features/shell/help-popover'
import { LayersMenu } from '@/features/shell/layers-menu'
import { clearActiveFrame, setActiveFrame } from '@/lib/geo'
import { useUiStore } from '@/lib/stores/ui-store'
import { wrapperFor } from '@/test/query-wrapper'
import { CameraControls } from './camera-controls'
import { FlatLayers } from './flat-layers'
import { roadSegment, osmRoad } from './flat-geometry'
import { LAYERS } from './layer-heights'
import { stageExtent } from './stage-extent'
import { useCameraStore } from './view-state'
import { Water } from './water'

describe('camera controls', () => {
  beforeEach(() => {
    useCameraStore.setState({ autoRotate: false, command: null })
    useUiStore.setState({ cameraPreset: 'overview' })
  })

  it('offers the five presets, Whole port, zoom, rotate and reset', () => {
    render(<CameraControls />)
    for (const name of ['Overview', 'Top', 'Yard', 'Quay', 'Ship', 'Whole port']) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    for (const name of ['Zoom in', 'Zoom out', 'Rotate', 'Reset view']) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    expect(screen.getByRole('button', { name: 'Overview' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('sends commands from the buttons', async () => {
    render(<CameraControls />)
    await userEvent.click(screen.getByRole('button', { name: 'Quay' }))
    expect(useCameraStore.getState().command).toMatchObject({ type: 'preset', key: 'quay' })
    await userEvent.click(screen.getByRole('button', { name: 'Whole port' }))
    expect(useCameraStore.getState().command).toMatchObject({ type: 'whole' })
    await userEvent.click(screen.getByRole('button', { name: 'Zoom in' }))
    expect(useCameraStore.getState().command).toMatchObject({ type: 'zoom' })
    await userEvent.click(screen.getByRole('button', { name: 'Rotate' }))
    expect(useCameraStore.getState().autoRotate).toBe(true)
    expect(screen.getByRole('button', { name: 'Rotate' })).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(screen.getByRole('button', { name: 'Reset view' }))
    expect(useCameraStore.getState()).toMatchObject({
      autoRotate: false,
      command: { type: 'preset', key: 'overview' },
    })
  })

  it('flies to a preset on keys 1-5, but not while typing', async () => {
    render(
      <>
        <CameraControls />
        <input aria-label="Search" />
      </>,
    )
    await userEvent.keyboard('4')
    expect(useCameraStore.getState().command).toMatchObject({ type: 'preset', key: 'quay' })
    await userEvent.keyboard('5')
    expect(useCameraStore.getState().command).toMatchObject({ key: 'ship' })
    const before = useCameraStore.getState().command
    await userEvent.keyboard('9')
    expect(useCameraStore.getState().command).toBe(before)
    await userEvent.click(screen.getByRole('textbox', { name: 'Search' }))
    await userEvent.keyboard('1')
    expect(useCameraStore.getState().command).toBe(before)
    await userEvent.keyboard('{Control>}2{/Control}')
    expect(useCameraStore.getState().command).toBe(before)
  })
})

describe('basemap and imagery note', () => {
  beforeEach(() => useUiStore.setState({ basemap: 'sentinel2', imageryFailed: false }))

  it('switches the satellite imagery off and on from All layers', async () => {
    render(<LayersMenu />)
    await userEvent.click(screen.getByRole('button', { name: 'All layers' }))
    const box = screen.getByRole('checkbox', { name: 'Satellite imagery (Sentinel-2)' })
    expect(box).toBeChecked()
    await userEvent.click(box)
    expect(useUiStore.getState().basemap).toBe('none')
    await userEvent.click(box)
    expect(useUiStore.getState().basemap).toBe('sentinel2')
  })

  it('lists the OSM roads layer', async () => {
    render(<LayersMenu />)
    await userEvent.click(screen.getByRole('button', { name: 'All layers' }))
    expect(screen.getByRole('checkbox', { name: 'Roads (OSM)' })).toBeChecked()
  })

  it('notes failed tiles quietly in Help, without a dialog', async () => {
    const Wrapper = wrapperFor()
    useUiStore.setState({ imageryFailed: true })
    render(<HelpPopover />, { wrapper: Wrapper })
    await userEvent.click(screen.getByRole('button', { name: 'Help' }))
    expect(screen.getByText(/Satellite imagery could not be loaded/)).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })
})

describe('stage layers in the scene graph', () => {
  beforeAll(() => setActiveFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 }))
  afterAll(() => clearActiveFrame())

  it('draw flat layers as one static mesh per layer and colour with polygon offsets', async () => {
    const items = [
      ...roadSegment(
        [
          [0, 0],
          [40, 0],
        ],
        8,
      ),
      ...osmRoad(
        [
          [0, 10],
          [40, 10],
        ],
        6,
      ),
    ]
    const renderer = await ReactThreeTestRenderer.create(<FlatLayers items={items} name="flat" />)
    const meshes = renderer.scene.findAll((n) => n.type === 'Mesh')
    expect(meshes.length).toBe(3) // road fill, white dashes, OSM road
    for (const m of meshes) {
      const material = (m.instance as Mesh).material as {
        polygonOffset: boolean
        polygonOffsetFactor: number
      }
      expect(material.polygonOffset).toBe(true)
      expect(material.polygonOffsetFactor).toBeLessThan(0)
    }
    const osm = meshes.find((m) => m.instance.name === `flat:osmRoad|${palette.osmRoad}`)!
    expect(
      ((osm.instance as Mesh).material as { polygonOffsetFactor: number }).polygonOffsetFactor,
    ).toBe(LAYERS.osmRoad.offset)
    // Land, roads and pads never move: advancing frames changes nothing
    const before = meshes.map((m) => m.instance.position.toArray().join())
    await renderer.advanceFrames(5, 0.1)
    expect(meshes.map((m) => m.instance.position.toArray().join())).toEqual(before)
    await renderer.unmount()
  })

  it('draws water, foam, quay wall and kerb; satellite mode makes the water 35% see-through', async () => {
    const extent = stageExtent({ landExtent: null, quayLine: null })
    const plain = await ReactThreeTestRenderer.create(<Water extent={extent} imagery={false} />)
    const surface = plain.scene.findByProps({ name: 'water-surface' })
    const solid = (surface.instance as Mesh).material as { transparent: boolean; opacity: number }
    expect(solid).toMatchObject({ transparent: false, opacity: 1 })
    expect(plain.scene.findByProps({ name: 'quay-wall' })).toBeDefined()
    expect(plain.scene.findByProps({ name: 'quay-kerb' })).toBeDefined()
    expect(plain.scene.findAll((n) => n.instance.name?.startsWith('flat:foam'))).toHaveLength(1)
    await plain.unmount()

    const over = await ReactThreeTestRenderer.create(<Water extent={extent} imagery />)
    const material = (over.scene.findByProps({ name: 'water-surface' }).instance as Mesh)
      .material as {
      transparent: boolean
      opacity: number
    }
    expect(material).toMatchObject({ transparent: true, opacity: 0.35 })
    await over.unmount()
  })
})
