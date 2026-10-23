import { beforeEach, describe, expect, it } from 'vitest'
import { UI_STORAGE_KEY, useUiStore } from './ui-store'

const initial = useUiStore.getState()

beforeEach(() => {
  useUiStore.setState(initial, true)
  sessionStorage.clear()
})

describe('useUiStore', () => {
  it('has the documented defaults', () => {
    expect(useUiStore.getState()).toMatchObject({
      mode: 'monitor',
      viewMode: 'split',
      panelHidden: false,
      paletteHidden: false,
      isolatedLayer: null,
      basemap: 'sentinel2',
    })
  })

  it('updates mode, tab, view mode, camera and toggles', () => {
    const ui = useUiStore.getState()
    ui.setMode('design')
    ui.setRightTab('versions')
    ui.setViewMode('3d')
    ui.setCameraPreset('top')
    ui.setLabels(false)
    ui.setAccuracyColouring(true)
    ui.setEnvelope(true)
    ui.setBasemap('none')

    expect(useUiStore.getState()).toMatchObject({
      mode: 'design',
      rightTab: 'versions',
      viewMode: '3d',
      cameraPreset: 'top',
      labels: false,
      accuracyColouring: true,
      envelope: true,
      basemap: 'none',
    })
  })

  it('tracks layer visibility and isolation', () => {
    useUiStore.getState().setLayerVisible('roads', false)
    useUiStore.getState().isolateLayer('quay')
    expect(useUiStore.getState().layerVisibility).toEqual({ roads: false })
    expect(useUiStore.getState().isolatedLayer).toBe('quay')

    useUiStore.getState().isolateLayer(null)
    expect(useUiStore.getState().isolatedLayer).toBeNull()
  })

  it('keeps only panel, palette and view mode in sessionStorage', () => {
    const ui = useUiStore.getState()
    ui.setPanelHidden(true)
    ui.setPaletteHidden(true)
    ui.setViewMode('2d')
    ui.setMode('design')
    ui.setLayerVisible('roads', false)

    const stored = JSON.parse(sessionStorage.getItem(UI_STORAGE_KEY) ?? '{}')
    expect(stored.state).toEqual({ panelHidden: true, paletteHidden: true, viewMode: '2d' })
    expect(localStorage.length).toBe(0)
  })
})
