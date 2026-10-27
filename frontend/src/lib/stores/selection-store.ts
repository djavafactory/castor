import { create } from 'zustand'

interface SelectionState {
  selectedAssetId: string | null
  hoveredAssetId: string | null
  /** OpenStreetMap reference (way/123) of the selected reference feature; reference features are read-only. */
  selectedReferenceId: string | null
  hoveredReferenceId: string | null
  select: (assetId: string | null) => void
  hover: (assetId: string | null) => void
  selectReference: (osmRef: string | null) => void
  hoverReference: (osmRef: string | null) => void
  clear: () => void
}

export const useSelectionStore = create<SelectionState>()((set) => ({
  selectedAssetId: null,
  hoveredAssetId: null,
  selectedReferenceId: null,
  hoveredReferenceId: null,
  // One thing is selected at a time: an asset or a reference feature.
  select: (selectedAssetId) =>
    set(
      selectedAssetId === null
        ? { selectedAssetId }
        : { selectedAssetId, selectedReferenceId: null },
    ),
  hover: (hoveredAssetId) => set({ hoveredAssetId }),
  selectReference: (selectedReferenceId) =>
    set(
      selectedReferenceId === null
        ? { selectedReferenceId }
        : { selectedReferenceId, selectedAssetId: null },
    ),
  hoverReference: (hoveredReferenceId) => set({ hoveredReferenceId }),
  clear: () =>
    set({
      selectedAssetId: null,
      hoveredAssetId: null,
      selectedReferenceId: null,
      hoveredReferenceId: null,
    }),
}))
