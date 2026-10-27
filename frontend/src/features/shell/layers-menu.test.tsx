import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useUiStore } from '@/lib/stores/ui-store'
import { LayersMenu } from './layers-menu'

beforeEach(() =>
  useUiStore.setState({
    layerVisibility: {},
    isolatedLayer: null,
    lockedLayers: {},
    mode: 'monitor',
  }),
)

describe('LayersMenu', () => {
  it('lists "Buildings (OSM)" under All layers, visible by default', async () => {
    render(<LayersMenu />)
    await userEvent.click(screen.getByRole('button', { name: 'All layers' }))

    expect(screen.getByRole('checkbox', { name: 'Show Buildings (OSM)' })).toBeChecked()
  })

  it('switches the buildings layer off and on', async () => {
    render(<LayersMenu />)
    await userEvent.click(screen.getByRole('button', { name: 'All layers' }))
    const box = screen.getByRole('checkbox', { name: 'Show Buildings (OSM)' })

    await userEvent.click(box)
    expect(useUiStore.getState().layerVisibility['buildings-osm']).toBe(false)
    expect(box).not.toBeChecked()

    await userEvent.click(box)
    expect(useUiStore.getState().layerVisibility['buildings-osm']).toBe(true)
  })

  it('is titled LAYERS and has a row with Show and Isolate for each layer', async () => {
    render(<LayersMenu />)
    await userEvent.click(screen.getByRole('button', { name: 'All layers' }))
    expect(screen.getByRole('heading', { name: 'Layers' })).toBeInTheDocument()
    for (const name of ['Terminals', 'Berths', 'Quay cranes', 'Mobile equipment', 'Roads (OSM)']) {
      expect(screen.getByRole('checkbox', { name: `Show ${name}` })).toBeChecked()
      expect(screen.getByRole('button', { name: `Isolate ${name}` })).toBeInTheDocument()
    }
    expect(screen.getAllByRole('checkbox', { name: /^Show / }).length).toBeGreaterThanOrEqual(18)
  })

  it('isolates a layer, and Show all restores everything', async () => {
    render(<LayersMenu />)
    await userEvent.click(screen.getByRole('button', { name: 'All layers' }))
    await userEvent.click(screen.getByRole('checkbox', { name: 'Show Roads' }))
    await userEvent.click(screen.getByRole('button', { name: 'Isolate Berths' }))
    expect(useUiStore.getState().isolatedLayer).toBe('berths')
    expect(screen.getByRole('button', { name: 'Isolate Berths' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )

    await userEvent.click(screen.getByRole('button', { name: 'Show all' }))
    expect(useUiStore.getState()).toMatchObject({ isolatedLayer: null, layerVisibility: {} })
    expect(screen.getByRole('checkbox', { name: 'Show Roads' })).toBeChecked()
  })

  it('isolating the same layer again ends the isolation', async () => {
    render(<LayersMenu />)
    await userEvent.click(screen.getByRole('button', { name: 'All layers' }))
    await userEvent.click(screen.getByRole('button', { name: 'Isolate Berths' }))
    await userEvent.click(screen.getByRole('button', { name: 'Isolate Berths' }))
    expect(useUiStore.getState().isolatedLayer).toBeNull()
  })

  it('offers locks in Design only, and not for the OpenStreetMap layers', async () => {
    const { unmount } = render(<LayersMenu />)
    await userEvent.click(screen.getByRole('button', { name: 'All layers' }))
    expect(screen.queryByRole('button', { name: 'Lock Berths' })).not.toBeInTheDocument()
    unmount()

    useUiStore.setState({ mode: 'design' })
    render(<LayersMenu />)
    await userEvent.click(screen.getByRole('button', { name: 'All layers' }))
    expect(screen.queryByRole('button', { name: 'Lock Buildings (OSM)' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Lock Berths' }))
    expect(useUiStore.getState().lockedLayers.berths).toBe(true)
    expect(screen.getByRole('button', { name: 'Lock Berths' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    // Show all does not unlock
    await userEvent.click(screen.getByRole('button', { name: 'Show all' }))
    expect(useUiStore.getState().lockedLayers.berths).toBe(true)
  })
})
