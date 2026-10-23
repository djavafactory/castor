import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSelectionStore } from './selection-store'

beforeEach(() => useSelectionStore.getState().clear())

describe('useSelectionStore', () => {
  it('selects, hovers and clears', () => {
    const { select, hover, clear } = useSelectionStore.getState()
    select('a')
    hover('b')
    expect(useSelectionStore.getState()).toMatchObject({
      selectedAssetId: 'a',
      hoveredAssetId: 'b',
    })

    clear()
    expect(useSelectionStore.getState()).toMatchObject({
      selectedAssetId: null,
      hoveredAssetId: null,
    })
  })

  it('lets transient subscribers react to hover without React', () => {
    const listener = vi.fn()
    const unsubscribe = useSelectionStore.subscribe((state) => listener(state.hoveredAssetId))

    useSelectionStore.getState().hover('crane-1')
    unsubscribe()
    useSelectionStore.getState().hover('crane-2')

    expect(listener).toHaveBeenCalledTimes(1)
    expect(listener).toHaveBeenCalledWith('crane-1')
  })
})
