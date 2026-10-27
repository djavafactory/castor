import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import { loadBasemap, saveBasemap, type Basemap } from '@/lib/basemap'
import { useAuthStore } from './auth-store'

export type Mode = 'monitor' | 'design'
export type RightTab = 'details' | 'catalogue' | 'inspector' | 'issues' | 'versions'
export type ViewMode = '2d' | '3d' | 'split'
export type { Basemap }

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
  /** Layer key -> locked (Design): no handles, no edits. */
  lockedLayers: Record<string, boolean>
  labels: boolean
  /** Static cargo fill of pads, stacks and coils. */
  cargo: boolean
  helpOpen: boolean
  accuracyColouring: boolean
  envelope: boolean
  basemap: Basemap
  /** True when the satellite tiles could not be drawn; the plain land colour is shown instead. */
  imageryFailed: boolean
  /** Id of the one open header or strip menu; opening one closes the other. */
  openMenu: string | null
  setMode: (mode: Mode) => void
  setRightTab: (tab: RightTab) => void
  setPanelHidden: (hidden: boolean) => void
  setPaletteHidden: (hidden: boolean) => void
  setViewMode: (viewMode: ViewMode) => void
  setCameraPreset: (preset: string) => void
  setLayerVisible: (layer: string, visible: boolean) => void
  isolateLayer: (layer: string | null) => void
  /** Shows every layer again: clears the switches and the isolation (locks stay). */
  showAllLayers: () => void
  setLayerLocked: (layer: string, locked: boolean) => void
  setCargo: (on: boolean) => void
  setHelpOpen: (open: boolean) => void
  setLabels: (on: boolean) => void
  setAccuracyColouring: (on: boolean) => void
  setEnvelope: (on: boolean) => void
  setBasemap: (basemap: Basemap) => void
  setImageryFailed: (failed: boolean) => void
  setOpenMenu: (menu: string | null) => void
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
      lockedLayers: {},
      labels: true,
      cargo: true,
      helpOpen: false,
      accuracyColouring: false,
      envelope: false,
      // The shell loads after the login, so the user is usually already known when this store is made
      basemap: loadBasemap(useAuthStore.getState().username),
      imageryFailed: false,
      openMenu: null,
      setMode: (mode) => set({ mode }),
      setRightTab: (rightTab) => set({ rightTab }),
      setPanelHidden: (panelHidden) => set({ panelHidden }),
      setPaletteHidden: (paletteHidden) => set({ paletteHidden }),
      setViewMode: (viewMode) => set({ viewMode }),
      setCameraPreset: (cameraPreset) => set({ cameraPreset }),
      setLayerVisible: (layer, visible) =>
        set((state) => ({ layerVisibility: { ...state.layerVisibility, [layer]: visible } })),
      isolateLayer: (isolatedLayer) => set({ isolatedLayer }),
      showAllLayers: () => set({ layerVisibility: {}, isolatedLayer: null }),
      setLayerLocked: (layer, locked) =>
        set((state) => ({ lockedLayers: { ...state.lockedLayers, [layer]: locked } })),
      setCargo: (cargo) => set({ cargo }),
      setHelpOpen: (helpOpen) => set({ helpOpen }),
      setLabels: (labels) => set({ labels }),
      setAccuracyColouring: (accuracyColouring) => set({ accuracyColouring }),
      setEnvelope: (envelope) => set({ envelope }),
      setBasemap: (basemap) => {
        set({ basemap })
        saveBasemap(useAuthStore.getState().username, basemap)
      },
      setImageryFailed: (imageryFailed) => set({ imageryFailed }),
      setOpenMenu: (openMenu) => set({ openMenu }),
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

/** The basemap is a view preference of the signed-in user: it follows them, not the layout, also at the next sign-in. */
useAuthStore.subscribe((state, previous) => {
  if (state.username !== previous.username) {
    useUiStore.setState({ basemap: loadBasemap(state.username) })
  }
})
