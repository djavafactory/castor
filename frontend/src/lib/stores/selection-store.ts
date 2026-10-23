import { create } from 'zustand'

interface SelectionState {
  selectedAssetId: string | null
  hoveredAssetId: string | null
  select: (assetId: string | null) => void
  hover: (assetId: string | null) => void
  clear: () => void
}

export const useSelectionStore = create<SelectionState>()((set) => ({
  selectedAssetId: null,
  hoveredAssetId: null,
  select: (selectedAssetId) => set({ selectedAssetId }),
  hover: (hoveredAssetId) => set({ hoveredAssetId }),
  clear: () => set({ selectedAssetId: null, hoveredAssetId: null }),
}))
