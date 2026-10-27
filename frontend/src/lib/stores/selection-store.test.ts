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

  it('selects a reference feature instead of an asset, and the other way round', () => {
    const { select, selectReference, hoverReference } = useSelectionStore.getState()
    select('a')
    selectReference('way/1')
    hoverReference('way/2')
    expect(useSelectionStore.getState()).toMatchObject({
      selectedAssetId: null,
      selectedReferenceId: 'way/1',
      hoveredReferenceId: 'way/2',
    })

    select('b')
    expect(useSelectionStore.getState()).toMatchObject({
      selectedAssetId: 'b',
      selectedReferenceId: null,
    })

    selectReference('way/3')
    selectReference(null)
    expect(useSelectionStore.getState().selectedReferenceId).toBeNull()
    expect(useSelectionStore.getState().selectedAssetId).toBeNull()
  })

  it('clears reference selection and hover too', () => {
    useSelectionStore.getState().selectReference('way/1')
    useSelectionStore.getState().hoverReference('way/1')
    useSelectionStore.getState().clear()
    expect(useSelectionStore.getState()).toMatchObject({
      selectedReferenceId: null,
      hoveredReferenceId: null,
    })
  })
})
