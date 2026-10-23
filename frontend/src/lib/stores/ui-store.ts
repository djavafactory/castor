import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'

export type Mode = 'monitor' | 'design'
export type RightTab = 'inspector' | 'versions' | 'details'
export type ViewMode = '2d' | '3d' | 'split'
export type Basemap = 'sentinel2' | 'none' | 'google'

interface UiState {
  mode: Mode
  rightTab: RightTab
  panelHidden: boolean
  paletteHidden: boolean
  viewMode: ViewMode
  cameraPreset: string
  /** Layer key -> visible. A missing key means visible. */
  layerVisibility: Record<string, boolean>
  /** When set, only this layer is shown. */
  isolatedLayer: string | null
  labels: boolean
  accuracyColouring: boolean
  envelope: boolean
  basemap: Basemap
  setMode: (mode: Mode) => void
  setRightTab: (tab: RightTab) => void
  setPanelHidden: (hidden: boolean) => void
  setPaletteHidden: (hidden: boolean) => void
  setViewMode: (viewMode: ViewMode) => void
  setCameraPreset: (preset: string) => void
  setLayerVisible: (layer: string, visible: boolean) => void
  isolateLayer: (layer: string | null) => void
  setLabels: (on: boolean) => void
  setAccuracyColouring: (on: boolean) => void
  setEnvelope: (on: boolean) => void
  setBasemap: (basemap: Basemap) => void
}

/** Only UI preferences are kept in sessionStorage; never credentials or layout data. */
export const UI_STORAGE_KEY = 'castor.ui'

export const useUiStore = create<UiState>()(
  persist(
    (set) => ({
      mode: 'monitor',
      rightTab: 'inspector',
      panelHidden: false,
      paletteHidden: false,
      viewMode: 'split',
      cameraPreset: 'overview',
      layerVisibility: {},
      isolatedLayer: null,
      labels: true,
      accuracyColouring: false,
      envelope: false,
      basemap: 'sentinel2',
      setMode: (mode) => set({ mode }),
      setRightTab: (rightTab) => set({ rightTab }),
      setPanelHidden: (panelHidden) => set({ panelHidden }),
      setPaletteHidden: (paletteHidden) => set({ paletteHidden }),
      setViewMode: (viewMode) => set({ viewMode }),
      setCameraPreset: (cameraPreset) => set({ cameraPreset }),
      setLayerVisible: (layer, visible) =>
        set((state) => ({ layerVisibility: { ...state.layerVisibility, [layer]: visible } })),
      isolateLayer: (isolatedLayer) => set({ isolatedLayer }),
      setLabels: (labels) => set({ labels }),
      setAccuracyColouring: (accuracyColouring) => set({ accuracyColouring }),
      setEnvelope: (envelope) => set({ envelope }),
      setBasemap: (basemap) => set({ basemap }),
    }),
    {
      name: UI_STORAGE_KEY,
      storage: createJSONStorage(() => sessionStorage),
      partialize: (state) => ({
        panelHidden: state.panelHidden,
        paletteHidden: state.paletteHidden,
        viewMode: state.viewMode,
      }),
    },
  ),
)
