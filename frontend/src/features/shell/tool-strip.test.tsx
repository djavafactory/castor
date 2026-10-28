import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { useEditorStore } from '@/lib/stores/editor-store'
import { guardLayerEdit, LAYER_LOCKED_MESSAGE } from '@/components/scene/layers'
import { useCameraStore } from '@/components/scene/stage/view-state'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { server } from '@/test/setup'
import { wrapperFor } from '@/test/query-wrapper'
import { FULLSCREEN_REFUSED } from './fullscreen'
import { ToolStrip } from './tool-strip'

vi.mock('sonner', () => ({ toast: Object.assign(vi.fn(), { error: vi.fn() }) }))

function renderStrip() {
  return render(<ToolStrip />, { wrapper: wrapperFor() })
}

beforeEach(() => {
  server.use(
    http.get('http://api.test/api/v1/version', () =>
      HttpResponse.json({
        version: __APP_VERSION__,
        commit: 'abc',
        buildTime: '',
        environment: 'test',
      }),
    ),
  )
  vi.mocked(toast).mockClear()
  useCameraStore.setState({ autoRotate: false, command: null })
  useUiStore.setState({
    mode: 'monitor',
    cameraPreset: 'overview',
    layerVisibility: {},
    isolatedLayer: null,
    lockedLayers: {},
    labels: true,
    cargo: true,
    panelHidden: false,
    helpOpen: false,
    envelope: false,
    accuracyColouring: false,
  })
})

describe('tool strip (Monitor)', () => {
  it('has the controls in order, with the two groups', () => {
    renderStrip()
    const names = within(screen.getByRole('toolbar', { name: 'Tools' }))
      .getAllByRole('button')
      .map((b) => b.getAttribute('aria-label') ?? b.textContent)
    expect(names).toEqual([
      'Camera position: Overview',
      'Zoom out',
      'Zoom in',
      'Rotate',
      'Reset',
      'Cargo',
      'Containers',
      'Labels',
      'All layers',
      'Hide panel',
      'Fullscreen',
      'More',
    ])
    const zoom = screen.getByRole('group', { name: 'Zoom and rotate' })
    expect(within(zoom).getAllByRole('button')).toHaveLength(4)
    expect(
      within(screen.getByRole('group', { name: 'Show in 3D' })).getAllByRole('button'),
    ).toHaveLength(4)
  })

  it('gives every control a tooltip and an accessible name', () => {
    renderStrip()
    for (const button of within(screen.getByRole('toolbar', { name: 'Tools' })).getAllByRole(
      'button',
    )) {
      expect(button).toHaveAccessibleName()
      expect(button).toHaveAttribute('title')
    }
    expect(screen.getByRole('button', { name: /^Camera position/ })).toHaveAttribute(
      'title',
      'Camera position in the 3D view (keys 1-5)',
    )
    expect(screen.getByRole('button', { name: 'Rotate' })).toHaveAttribute(
      'title',
      'Rotate the 3D view around the port',
    )
    expect(screen.getByRole('button', { name: 'Reset' })).toHaveAttribute(
      'title',
      'Back to the overview',
    )
    expect(screen.getByRole('button', { name: 'Hide panel' })).toHaveAttribute(
      'title',
      'Hide or show the side panel (P)',
    )
    expect(screen.getByRole('button', { name: 'Fullscreen' })).toHaveAttribute(
      'title',
      'Fullscreen (F)',
    )
  })

  it('flies to a preset from the camera dropdown and shows the current one', async () => {
    const { rerender } = renderStrip()
    await userEvent.click(screen.getByRole('button', { name: /^Camera position/ }))
    expect(screen.getAllByRole('menuitem').map((i) => i.textContent)).toEqual([
      'Overview1',
      'Top2',
      'Yard3',
      'Quay4',
      'Ship5',
      'Whole port',
    ])
    await userEvent.click(screen.getByRole('menuitem', { name: /^Quay/ }))
    expect(useCameraStore.getState().command).toMatchObject({ type: 'preset', key: 'quay' })

    useUiStore.setState({ cameraPreset: 'quay' })
    rerender(<ToolStrip />)
    expect(screen.getByRole('button', { name: 'Camera position: Quay' })).toBeInTheDocument()
  })

  it('zooms, rotates and resets', async () => {
    renderStrip()
    await userEvent.click(screen.getByRole('button', { name: 'Zoom in' }))
    expect(useCameraStore.getState().command).toMatchObject({ type: 'zoom' })
    await userEvent.click(screen.getByRole('button', { name: 'Rotate' }))
    expect(screen.getByRole('button', { name: 'Rotate' })).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(screen.getByRole('button', { name: 'Reset' }))
    expect(useCameraStore.getState()).toMatchObject({
      autoRotate: false,
      command: { type: 'preset', key: 'overview' },
    })
  })

  it('toggles Cargo, Containers and Labels with aria-pressed', async () => {
    renderStrip()
    for (const name of ['Cargo', 'Containers', 'Labels']) {
      const button = screen.getByRole('button', { name })
      expect(button).toHaveAttribute('aria-pressed', 'true')
      await userEvent.click(button)
      expect(button).toHaveAttribute('aria-pressed', 'false')
    }
    expect(useUiStore.getState()).toMatchObject({ cargo: false, labels: false })
    expect(useUiStore.getState().layerVisibility.containers).toBe(false)
  })

  it('flips the panel button text, and tells when fullscreen is refused', async () => {
    document.documentElement.requestFullscreen = vi.fn().mockRejectedValue(new Error('no'))
    renderStrip()
    await userEvent.click(screen.getByRole('button', { name: 'Hide panel' }))
    expect(screen.getByRole('button', { name: 'Show panel' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Fullscreen' }))
    expect(toast).toHaveBeenCalledWith(FULLSCREEN_REFUSED)
  })

  it('opens Help and legend from More: accuracy, synthetic, keyboard and attribution', async () => {
    renderStrip()
    await userEvent.click(screen.getByRole('button', { name: 'More' }))
    expect(screen.queryByRole('button', { name: 'Verify accuracy' })).not.toBeInTheDocument()
    const help = screen.getByRole('button', { name: /Help and legend/ })
    expect(help).toHaveAttribute('title', 'Legend, accuracy labels and keyboard shortcuts (?)')
    await userEvent.click(help)

    for (const text of ['Surveyed', 'Imported verified', 'Operator-entered', 'Approximate']) {
      expect(screen.getByText(text)).toBeInTheDocument()
    }
    for (const heading of ['Accuracy of shapes', 'Synthetic', 'Keyboard', 'Attribution']) {
      expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument()
    }
    expect(screen.getByText(/Sentinel-2 cloudless by EOX IT Services GmbH/)).toBeInTheDocument()
    const keys = screen.getAllByRole('rowheader').map((r) => r.textContent)
    expect(keys).toEqual(['1-5', 'P', 'F', 'L', '?', 'Esc'])
  })

  it('opens the help on the ? key state and lists the Design keys in Design', () => {
    useUiStore.setState({ mode: 'design', helpOpen: true })
    renderStrip()
    const keys = screen.getAllByRole('rowheader').map((r) => r.textContent)
    expect(keys).toEqual([
      '1-5',
      'P',
      'L',
      '?',
      'Esc',
      'A',
      'R',
      'M',
      'Ctrl+Z / Ctrl+Y',
      'Ctrl+S',
      'Delete',
      'Enter',
      'Backspace',
    ])
  })

  it('shows the debug switches in the Monitor Help menu', async () => {
    renderStrip()
    await userEvent.click(screen.getByRole('button', { name: 'More' }))
    await userEvent.click(screen.getByRole('switch', { name: 'Envelope boxes' }))
    await userEvent.click(screen.getByRole('switch', { name: 'Accuracy outlines' }))
    expect(useUiStore.getState()).toMatchObject({ envelope: true, accuracyColouring: true })
  })
})

describe('tool strip (Design)', () => {
  beforeEach(() => {
    useUiStore.setState({ mode: 'design' })
    useEditorStore.getState().reset()
  })

  it('has no Fullscreen, adds Verify accuracy to More and keeps the switches out of it', async () => {
    renderStrip()
    expect(screen.queryByRole('button', { name: 'Fullscreen' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'More' }))
    expect(screen.getByRole('button', { name: 'Verify accuracy' })).toBeInTheDocument()
    expect(screen.queryByRole('switch', { name: 'Envelope boxes' })).not.toBeInTheDocument()
  })

  it('disables Undo and Redo with nothing to undo or redo', () => {
    renderStrip()
    expect(screen.getByRole('button', { name: 'Undo' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Redo' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Undo' })).toHaveAttribute('title', 'Undo (Ctrl+Z)')
    expect(screen.getByRole('button', { name: 'Redo' })).toHaveAttribute('title', 'Redo (Ctrl+Y)')
  })

  it('undoes and redoes a step from the toolbar', async () => {
    const undo = vi.fn()
    const redo = vi.fn()
    useEditorStore.getState().pushHistory({ label: 'Moved Shed 1', undo, redo })
    renderStrip()

    const undoButton = screen.getByRole('button', { name: 'Undo' })
    expect(undoButton).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Redo' })).toBeDisabled()
    await userEvent.click(undoButton)
    expect(undo).toHaveBeenCalledOnce()

    const redoButton = screen.getByRole('button', { name: 'Redo' })
    expect(redoButton).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Undo' })).toBeDisabled()
    await userEvent.click(redoButton)
    expect(redo).toHaveBeenCalledOnce()
  })
})

describe('locked layers', () => {
  it('lets an edit through on an unlocked layer and refuses it on a locked one', () => {
    expect(guardLayerEdit('BERTH', 'MARINE')).toBe(true)
    useUiStore.setState({ lockedLayers: { berths: true } })
    expect(guardLayerEdit('BERTH', 'MARINE')).toBe(false)
    expect(toast).toHaveBeenCalledWith(LAYER_LOCKED_MESSAGE)
    expect(LAYER_LOCKED_MESSAGE).toBe('That layer is locked. Unlock it in Layers to edit.')
  })
})

describe('tool strip (Design): draft buttons', () => {
  beforeEach(() =>
    useUiStore.setState({ mode: 'design', panelHidden: true, rightTab: 'inspector' }),
  )

  it('has Save draft (check and save) and a primary Publish with their tooltips', async () => {
    renderStrip()

    const before = useEditorStore.getState().saveRequest
    const save = screen.getByRole('button', { name: 'Save draft' })
    expect(save).toHaveAttribute('title', 'Check and save (Ctrl+S)')
    await userEvent.click(save)
    expect(useEditorStore.getState().saveRequest).toBe(before + 1)
    expect(screen.getByRole('button', { name: 'Publish' })).toHaveAttribute(
      'title',
      'Validate and publish an immutable version',
    )
  })

  it('opens the Versions tab first when Publish is pressed, showing the panel if it was hidden', async () => {
    renderStrip()

    await userEvent.click(screen.getByRole('button', { name: 'Publish' }))

    expect(useUiStore.getState().rightTab).toBe('versions')
    expect(useUiStore.getState().panelHidden).toBe(false)
  })

  it('does not show the draft buttons in Monitor', () => {
    useUiStore.setState({ mode: 'monitor' })
    renderStrip()

    expect(screen.queryByRole('button', { name: 'Publish' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save draft' })).not.toBeInTheDocument()
  })
})
