import { fireEvent, render } from '@testing-library/react'
import { toast } from 'sonner'
import { useCameraStore } from '@/components/scene/stage/view-state'
import { basicCredential, useAuthStore } from '@/lib/stores/auth-store'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { FULLSCREEN_REFUSED } from './fullscreen'
import { useSceneKeys } from './use-scene-keys'

vi.mock('sonner', () => ({ toast: Object.assign(vi.fn(), { error: vi.fn() }) }))

function Host() {
  useSceneKeys()
  return <input aria-label="field" />
}

function signIn() {
  useAuthStore.getState().signIn({
    username: 'ada',
    displayName: 'Ada',
    roles: ['EDITOR'],
    credential: basicCredential('ada', 'secret'),
  })
}

beforeEach(() => {
  vi.mocked(toast).mockClear()
  signIn()
  useEditorStore.getState().reset()
  useCameraStore.setState({ command: null })
  useUiStore.setState({
    mode: 'monitor',
    labels: true,
    panelHidden: false,
    paletteHidden: false,
    helpOpen: false,
  })
})

describe('scene keys', () => {
  it('toggles labels with L and clears the selection with Esc', () => {
    useSelectionStore.getState().select('a1')
    render(<Host />)

    fireEvent.keyDown(document.body, { key: 'l' })
    expect(useUiStore.getState().labels).toBe(false)
    fireEvent.keyDown(document.body, { key: 'Escape' })
    expect(useSelectionStore.getState().selectedAssetId).toBeNull()
  })

  it('flies to a preset on 1-5 and ignores other digits', () => {
    render(<Host />)
    fireEvent.keyDown(document.body, { key: '4' })
    expect(useCameraStore.getState().command).toMatchObject({ type: 'preset', key: 'quay' })
    const before = useCameraStore.getState().command
    fireEvent.keyDown(document.body, { key: '9' })
    expect(useCameraStore.getState().command).toBe(before)
  })

  it('hides the panel with P and opens the help with ?', () => {
    render(<Host />)
    fireEvent.keyDown(document.body, { key: 'p' })
    expect(useUiStore.getState().panelHidden).toBe(true)
    fireEvent.keyDown(document.body, { key: '?', shiftKey: true })
    expect(useUiStore.getState().helpOpen).toBe(true)
  })

  it('tries fullscreen on F in Monitor and says so when the window refuses', async () => {
    document.documentElement.requestFullscreen = vi.fn().mockRejectedValue(new Error('no'))
    render(<Host />)
    fireEvent.keyDown(document.body, { key: 'f' })
    await vi.waitFor(() => expect(toast).toHaveBeenCalledWith(FULLSCREEN_REFUSED))
    expect(document.documentElement.requestFullscreen).toHaveBeenCalled()
  })

  it('ignores keys typed in a field', () => {
    const { getByLabelText } = render(<Host />)
    fireEvent.keyDown(getByLabelText('field'), { key: 'l' })
    fireEvent.keyDown(getByLabelText('field'), { key: 'p' })
    expect(useUiStore.getState().labels).toBe(true)
    expect(useUiStore.getState().panelHidden).toBe(false)
  })

  it('ignores keys with Ctrl, Meta or Alt, and when signed out', () => {
    render(<Host />)
    fireEvent.keyDown(document.body, { key: 'l', ctrlKey: true })
    fireEvent.keyDown(document.body, { key: 'l', metaKey: true })
    fireEvent.keyDown(document.body, { key: 'l', altKey: true })
    expect(useUiStore.getState().labels).toBe(true)
    useAuthStore.getState().clearSession()
    fireEvent.keyDown(document.body, { key: 'l' })
    expect(useUiStore.getState().labels).toBe(true)
  })

  describe('Design', () => {
    beforeEach(() => useUiStore.setState({ mode: 'design' }))

    it('does not use F, and A hides the palette', () => {
      document.documentElement.requestFullscreen = vi.fn().mockResolvedValue(undefined)
      render(<Host />)
      fireEvent.keyDown(document.body, { key: 'f' })
      expect(document.documentElement.requestFullscreen).not.toHaveBeenCalled()
      fireEvent.keyDown(document.body, { key: 'a' })
      expect(useUiStore.getState().paletteHidden).toBe(true)
    })

    it('M measures, R turns the ghost, Backspace removes the last point, Esc leaves the tool', () => {
      render(<Host />)
      fireEvent.keyDown(document.body, { key: 'm' })
      expect(useEditorStore.getState().tool?.kind).toBe('measure')
      fireEvent.keyDown(document.body, { key: 'r' })
      expect(useEditorStore.getState().tool?.rotationDeg).toBe(15)
      // the turn wraps into -180..180
      useEditorStore.getState().setRotation(170)
      fireEvent.keyDown(document.body, { key: 'R' })
      expect(useEditorStore.getState().tool?.rotationDeg).toBe(-175)
      useEditorStore.getState().addPoint([1, 2])
      fireEvent.keyDown(document.body, { key: 'Backspace' })
      expect(useEditorStore.getState().tool?.points).toEqual([])
      fireEvent.keyDown(document.body, { key: 'Escape' })
      expect(useEditorStore.getState().tool).toBeNull()
      // M again leaves the Measure tool
      fireEvent.keyDown(document.body, { key: 'm' })
      fireEvent.keyDown(document.body, { key: 'm' })
      expect(useEditorStore.getState().tool).toBeNull()
    })

    it('Ctrl+Z undoes and Ctrl+Y redoes, without the browser default', () => {
      const undo = vi.fn()
      const redo = vi.fn()
      useEditorStore.getState().pushHistory({ label: 'move', undo, redo })
      render(<Host />)
      const z = new KeyboardEvent('keydown', {
        key: 'z',
        ctrlKey: true,
        cancelable: true,
        bubbles: true,
      })
      document.body.dispatchEvent(z)
      expect(z.defaultPrevented).toBe(true)
      expect(undo).toHaveBeenCalledOnce()
      fireEvent.keyDown(document.body, { key: 'y', ctrlKey: true })
      expect(redo).toHaveBeenCalledOnce()
    })

    it('Cmd+Z undoes and Cmd+Shift+Z redoes, without the browser default (Mac)', () => {
      const undo = vi.fn()
      const redo = vi.fn()
      useEditorStore.getState().pushHistory({ label: 'move', undo, redo })
      render(<Host />)
      const z = new KeyboardEvent('keydown', {
        key: 'z',
        metaKey: true,
        cancelable: true,
        bubbles: true,
      })
      document.body.dispatchEvent(z)
      expect(z.defaultPrevented).toBe(true)
      expect(undo).toHaveBeenCalledOnce()
      fireEvent.keyDown(document.body, { key: 'z', metaKey: true, shiftKey: true })
      expect(redo).toHaveBeenCalledOnce()
    })

    it('leaves Ctrl+Z alone in Monitor', () => {
      useUiStore.setState({ mode: 'monitor' })
      const undo = vi.fn()
      useEditorStore.getState().pushHistory({ label: 'move', undo, redo: vi.fn() })
      render(<Host />)
      fireEvent.keyDown(document.body, { key: 'z', ctrlKey: true })
      expect(undo).not.toHaveBeenCalled()
    })
  })
})
