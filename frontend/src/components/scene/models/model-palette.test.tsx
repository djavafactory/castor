import { render, screen, within } from '@testing-library/react'
import { ModelPalette } from './model-palette'
import { MODEL_SPECS } from './registry'

describe('ModelPalette', () => {
  it('lists every model under cranes, handling equipment or vehicles', () => {
    render(<ModelPalette />)
    expect(screen.getAllByRole('listitem')).toHaveLength(MODEL_SPECS.length)
    const cranes = screen.getByRole('region', { name: 'Cranes' })
    expect(within(cranes).getByText('Ship-to-shore crane')).toBeInTheDocument()
    const handling = screen.getByRole('region', { name: 'Handling equipment' })
    expect(within(handling).getByText('Excavator')).toBeInTheDocument()
    const vehicles = screen.getByRole('region', { name: 'Vehicles' })
    expect(within(vehicles).getByText('RoRo car transporter')).toBeInTheDocument()
  })
})
