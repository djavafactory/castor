import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useUiStore } from '@/lib/stores/ui-store'
import { LayersMenu } from './layers-menu'

beforeEach(() => useUiStore.setState({ layerVisibility: {}, isolatedLayer: null }))

describe('LayersMenu', () => {
  it('lists "Buildings (OSM)" under All layers, visible by default', async () => {
    render(<LayersMenu />)
    await userEvent.click(screen.getByRole('button', { name: 'All layers' }))

    expect(screen.getByRole('checkbox', { name: 'Buildings (OSM)' })).toBeChecked()
  })

  it('switches the buildings layer off and on', async () => {
    render(<LayersMenu />)
    await userEvent.click(screen.getByRole('button', { name: 'All layers' }))
    const box = screen.getByRole('checkbox', { name: 'Buildings (OSM)' })

    await userEvent.click(box)
    expect(useUiStore.getState().layerVisibility['buildings-osm']).toBe(false)
    expect(box).not.toBeChecked()

    await userEvent.click(box)
    expect(useUiStore.getState().layerVisibility['buildings-osm']).toBe(true)
  })
})
