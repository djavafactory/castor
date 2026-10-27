import { render, screen } from '@testing-library/react'
import ReactThreeTestRenderer from '@react-three/test-renderer'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LayersMenu } from '@/features/shell/layers-menu'
import { useUiStore } from '@/lib/stores/ui-store'

const flag = vi.hoisted(() => ({ on: true }))
vi.mock('@/lib/features', () => ({ containersEnabled: () => flag.on }))

import { layers, CONTAINERS_LAYER } from '../layers'
import { ContainerTerminalLayer } from './container-terminal-layer'

afterEach(() => {
  flag.on = true
  useUiStore.setState({ layerVisibility: {}, isolatedLayer: null })
})

describe('Containers switch and feature flag', () => {
  it('lists "Containers" in All layers when the feature is on, switchable', async () => {
    render(<LayersMenu />)
    await userEvent.click(screen.getByRole('button', { name: 'All layers' }))
    const box = screen.getByRole('checkbox', { name: 'Containers' })
    expect(box).toBeChecked()
    await userEvent.click(box)
    expect(useUiStore.getState().layerVisibility[CONTAINERS_LAYER]).toBe(false)
  })

  it('has no Containers layer when the feature is off', async () => {
    flag.on = false
    expect(layers().map((l) => l.key)).not.toContain(CONTAINERS_LAYER)
    render(<LayersMenu />)
    await userEvent.click(screen.getByRole('button', { name: 'All layers' }))
    expect(screen.queryByRole('checkbox', { name: 'Containers' })).toBeNull()
    expect(screen.getByRole('checkbox', { name: 'Buildings (OSM)' })).toBeInTheDocument()
  })

  const yard = {
    id: 'cy1',
    code: 'CY-1',
    length: 120,
    width: 60,
    maxTiers: 4,
    designFill: 0.6,
    position: [10, 0, 20] as [number, number, number],
    heading: 0,
  }
  const crane = {
    id: 'sts1',
    modelKey: 'STS_CRANE',
    size: { length: 30, width: 20, height: 38 },
    position: [0, 0, 0] as [number, number, number],
    heading: 0,
  }

  it('draws yards and cranes while the feature and the layer are on', async () => {
    const renderer = await ReactThreeTestRenderer.create(
      <ContainerTerminalLayer yards={[yard]} cranes={[crane]} />,
    )
    expect(renderer.scene.instance.getObjectByName('container-yard-CY-1')).toBeDefined()
    expect(renderer.scene.instance.getObjectByName('asset-visual-STS_CRANE')).toBeDefined()
    await renderer.unmount()
  })

  it('draws nothing with the layer switched off', async () => {
    useUiStore.setState({ layerVisibility: { [CONTAINERS_LAYER]: false } })
    const renderer = await ReactThreeTestRenderer.create(
      <ContainerTerminalLayer yards={[yard]} cranes={[crane]} />,
    )
    expect(renderer.scene.children).toHaveLength(0)
    await renderer.unmount()
  })

  it('draws nothing with the feature off', async () => {
    flag.on = false
    const renderer = await ReactThreeTestRenderer.create(
      <ContainerTerminalLayer yards={[yard]} cranes={[crane]} />,
    )
    expect(renderer.scene.children).toHaveLength(0)
    await renderer.unmount()
  })
})
