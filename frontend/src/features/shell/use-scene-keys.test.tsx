import { fireEvent, render } from '@testing-library/react'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { useSceneKeys } from './use-scene-keys'

function Host() {
  useSceneKeys()
  return <input aria-label="field" />
}

describe('scene keys', () => {
  it('toggles labels with L and clears the selection with Esc', () => {
    useUiStore.setState({ labels: true })
    useSelectionStore.getState().select('a1')
    render(<Host />)

    fireEvent.keyDown(document.body, { key: 'l' })
    expect(useUiStore.getState().labels).toBe(false)
    fireEvent.keyDown(document.body, { key: 'Escape' })
    expect(useSelectionStore.getState().selectedAssetId).toBeNull()
  })

  it('ignores keys typed in a field', () => {
    useUiStore.setState({ labels: true })
    const { getByLabelText } = render(<Host />)
    fireEvent.keyDown(getByLabelText('field'), { key: 'l' })
    expect(useUiStore.getState().labels).toBe(true)
  })
})
