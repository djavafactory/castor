import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useEditorStore } from '@/lib/stores/editor-store'
import { API, assetTypesFixture } from '@/test/fixtures'
import { wrapperFor } from '@/test/query-wrapper'
import { server } from '@/test/setup'
import { AssetPalette, DragChip, GROUP_LABELS } from './asset-palette'
import { DRAG_START_PX } from './place/palette-drag'
import { usePlacementStore } from './place/use-placement'

vi.mock('sonner', () => ({ toast: vi.fn() }))

const GROUPS = Object.keys(GROUP_LABELS)
const base = assetTypesFixture[0]!

/** 56 types over the eight groups, like the real registry: seven per group, every seventh one a draw type. */
const types = GROUPS.flatMap((group, g) =>
  Array.from({ length: 7 }, (_, i) => ({
    ...base,
    code: `${group}_${i}`,
    label: `${GROUP_LABELS[group]!.split(' ')[0]} thing ${i}`,
    paletteGroup: group,
    placementMode: i === 6 ? 'DRAW' : 'PLACE',
    geometryKind: i === 6 ? 'POLYGON' : 'POINT',
    modelKey: 'no-such-model',
    hint: `Hint ${group}${i}.`,
    sortOrder: g * 10 + i,
  })),
)

beforeEach(() => {
  useEditorStore.getState().reset()
  usePlacementStore.setState({ plan: null, problem: null, dragging: null, pointer: null })
  server.use(http.get(`${API}/v1/asset-types`, () => HttpResponse.json(types)))
})

async function palette() {
  render(<AssetPalette />, { wrapper: wrapperFor() })
  await screen.findByTestId('palette-group-CRANES')
}

describe('asset palette', () => {
  it('lists the eight groups in palette order with every one of the 56 types reachable', async () => {
    await palette()
    const headings = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)
    expect(headings).toEqual(Object.values(GROUP_LABELS))
    // open every group: all 56 tiles are there
    for (const group of GROUPS.slice(1)) {
      fireEvent.click(within(screen.getByTestId(`palette-group-${group}`)).getByRole('button'))
    }
    expect(screen.getAllByTestId(/^palette-tile-/)).toHaveLength(56)
  })

  it('opens only the first group at first; the header toggles it with aria-expanded', async () => {
    await palette()
    const expanded = (group: string) =>
      within(screen.getByTestId(`palette-group-${group}`))
        .getByRole('button', { name: GROUP_LABELS[group] })
        .getAttribute('aria-expanded')
    expect(expanded('CRANES')).toBe('true')
    expect(expanded('HANDLING')).toBe('false')
    fireEvent.click(within(screen.getByTestId('palette-group-HANDLING')).getByRole('button'))
    expect(expanded('HANDLING')).toBe('true')
    fireEvent.click(
      within(screen.getByTestId('palette-group-CRANES')).getByRole('button', {
        name: GROUP_LABELS.CRANES,
      }),
    )
    expect(expanded('CRANES')).toBe('false')
  })

  it('searches label or group, case-insensitively, and forces matching groups open', async () => {
    await palette()
    const input = screen.getByRole('searchbox', { name: 'Search assets' })
    fireEvent.change(input, { target: { value: 'ROADS' } })
    expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual([
      'Roads',
    ])
    expect(screen.getAllByTestId(/^palette-tile-/)).toHaveLength(7)

    fireEvent.change(input, { target: { value: 'material thing 3' } })
    expect(screen.getAllByTestId(/^palette-tile-/)).toHaveLength(1)
    expect(screen.getByTestId('palette-tile-HANDLING_3')).toBeInTheDocument()

    fireEvent.change(input, { target: { value: 'zzz' } })
    expect(screen.getByText('No asset matches.')).toBeInTheDocument()
  })

  it('labels draw types "(draw)", shows the hint tooltip and a coloured icon until the model exists', async () => {
    await palette()
    const draw = screen.getByTestId('palette-tile-CRANES_6')
    expect(draw).toHaveTextContent('Cranes thing 6 (draw)')
    expect(screen.getByTestId('palette-tile-CRANES_0')).toHaveAttribute(
      'title',
      'Hint CRANES0. Click to place, or drag onto the map.',
    )
    expect(
      within(screen.getByTestId('palette-tile-CRANES_0')).getByTestId('type-icon'),
    ).toBeInTheDocument()
  })

  it('starts the place tool, highlights the tile, and a second click cancels', async () => {
    await palette()
    const tile = screen.getByTestId('palette-tile-CRANES_0')
    fireEvent.click(tile)
    expect(useEditorStore.getState().tool).toMatchObject({
      kind: 'place',
      typeCode: 'CRANES_0',
      rotationDeg: 0,
    })
    expect(tile).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(tile)
    expect(useEditorStore.getState().tool).toBeNull()
    expect(tile).toHaveAttribute('aria-pressed', 'false')
  })

  it('starts the draw tool for a draw type', async () => {
    await palette()
    fireEvent.click(screen.getByTestId('palette-tile-CRANES_6'))
    expect(useEditorStore.getState().tool).toMatchObject({ kind: 'draw', typeCode: 'CRANES_6' })
  })

  it('turns a press into a drag after 6 px: the chip follows, and the click that ends it starts no tool', async () => {
    render(
      <>
        <AssetPalette />
        <DragChip />
      </>,
      { wrapper: wrapperFor() },
    )
    const tile = await screen.findByTestId('palette-tile-CRANES_0')
    fireEvent.pointerDown(tile, { button: 0, clientX: 100, clientY: 100 })
    fireEvent.pointerMove(window, { clientX: 100 + DRAG_START_PX, clientY: 100 })
    expect(usePlacementStore.getState().dragging).toBeNull()
    fireEvent.pointerMove(window, { clientX: 100 + DRAG_START_PX + 2, clientY: 100 })
    await waitFor(() => expect(screen.getByTestId('drag-chip')).toHaveTextContent('Cranes thing 0'))
    fireEvent.pointerUp(window)
    await waitFor(() => expect(usePlacementStore.getState().dragging).toBeNull())
    fireEvent.click(tile)
    expect(useEditorStore.getState().tool).toBeNull()
  })
})
