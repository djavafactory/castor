import {
  Camera,
  ChevronDown,
  Container,
  Maximize,
  Minimize,
  Orbit,
  Package,
  PanelLeft,
  PanelRight,
  RotateCcw,
  Tag,
  ZoomIn,
  ZoomOut,
} from 'lucide-react'
import type { ReactNode } from 'react'
import { Button, IconButton } from '@/components/ui/button'
import { Menu, MenuContent, MenuItem, MenuTrigger } from '@/components/ui/overlays'
import { Segmented } from '@/components/ui/segmented'
import { CONTAINERS_LAYER } from '@/components/scene/layers'
import { useMapStore } from '@/components/map/map-store'
import { PRESETS, presetByKey } from '@/components/scene/stage/camera-presets'
import { camera, useCameraStore } from '@/components/scene/stage/view-state'
import { containersEnabled } from '@/lib/features'
import { useUiStore, type ViewMode } from '@/lib/stores/ui-store'
import { toggleFullscreen, useFullscreen } from './fullscreen'
import { LayersMenu } from './layers-menu'
import { MoreMenu } from './more-menu'
import { showsMap, showsScene } from './view-modes'

/** The +, - and Reset buttons move whichever views are on screen: the 2D map, the 3D camera, or both. */
function zoomViews(direction: 'in' | 'out') {
  const { mode, viewMode } = useUiStore.getState()
  if (showsMap(mode, viewMode)) {
    if (direction === 'in') useMapStore.getState().zoomIn()
    else useMapStore.getState().zoomOut()
  }
  if (showsScene(mode, viewMode)) {
    if (direction === 'in') camera.zoomIn()
    else camera.zoomOut()
  }
}

function resetViews() {
  const { mode, viewMode } = useUiStore.getState()
  if (showsMap(mode, viewMode)) useMapStore.getState().reset()
  if (showsScene(mode, viewMode)) camera.reset()
}

const VIEW_MODES: { value: ViewMode; label: string }[] = [
  { value: '2d', label: '2D' },
  { value: '3d', label: '3D' },
  { value: 'split', label: 'Split' },
]

function Group({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div role="group" aria-label={label} className="flex items-center gap-0.5">
      {children}
    </div>
  )
}

/** Camera icon, the current preset and a chevron; the items fly to a preset (keys 1-5). */
function CameraMenu() {
  const current = useUiStore((s) => s.cameraPreset)
  const label = current === 'custom' ? 'Custom' : (presetByKey(current)?.label ?? 'Overview')
  return (
    <Menu>
      <MenuTrigger asChild>
        <button
          type="button"
          aria-label={`Camera position: ${label}`}
          title="Camera position in the 3D view (keys 1-5)"
          className="inline-flex h-7 items-center gap-1.5 rounded-lg px-2 text-[13px] font-medium hover:bg-paper"
        >
          <Camera aria-hidden="true" className="size-4" />
          {label}
          <ChevronDown aria-hidden="true" className="size-3.5" />
        </button>
      </MenuTrigger>
      <MenuContent align="start">
        {PRESETS.map((preset, i) => (
          <MenuItem key={preset.key} onSelect={() => camera.preset(preset.key)}>
            <span className="flex-1">{preset.label}</span>
            <kbd className="font-mono text-xs text-muted">{i + 1}</kbd>
          </MenuItem>
        ))}
        <MenuItem onSelect={() => camera.wholePort()}>Whole port</MenuItem>
      </MenuContent>
    </Menu>
  )
}

/**
 * The strip under the app bar, shared by both modes: camera, zoom and rotate, what to show in 3D, the side panel,
 * fullscreen (Monitor) and the More menu. Design adds its view switch and palette button on the left.
 * Nothing floats over it (stage overlays sit below it).
 */
export function ToolStrip() {
  const mode = useUiStore((s) => s.mode)
  const viewMode = useUiStore((s) => s.viewMode)
  const setViewMode = useUiStore((s) => s.setViewMode)
  const panelHidden = useUiStore((s) => s.panelHidden)
  const setPanelHidden = useUiStore((s) => s.setPanelHidden)
  const labels = useUiStore((s) => s.labels)
  const setLabels = useUiStore((s) => s.setLabels)
  const cargo = useUiStore((s) => s.cargo)
  const setCargo = useUiStore((s) => s.setCargo)
  const containers = useUiStore((s) => s.layerVisibility[CONTAINERS_LAYER] !== false)
  const setLayerVisible = useUiStore((s) => s.setLayerVisible)
  const paletteHidden = useUiStore((s) => s.paletteHidden)
  const setPaletteHidden = useUiStore((s) => s.setPaletteHidden)
  const rotating = useCameraStore((s) => s.autoRotate)
  const fullscreen = useFullscreen()

  return (
    <div
      role="toolbar"
      aria-label="Tools"
      className="relative z-strip flex items-center gap-2 border-b border-line-soft bg-paper-2 px-4"
    >
      {mode === 'design' && (
        <>
          <Segmented label="View" value={viewMode} options={VIEW_MODES} onChange={setViewMode} />
          <IconButton
            label="Hide palette"
            tip="Hide or show the asset palette (A)"
            pressed={paletteHidden}
            onClick={() => setPaletteHidden(!paletteHidden)}
          >
            <PanelLeft aria-hidden="true" />
          </IconButton>
        </>
      )}
      <CameraMenu />
      <Group label="Zoom and rotate">
        <IconButton label="Zoom out" onClick={() => zoomViews('out')}>
          <ZoomOut aria-hidden="true" />
        </IconButton>
        <IconButton label="Zoom in" onClick={() => zoomViews('in')}>
          <ZoomIn aria-hidden="true" />
        </IconButton>
        <IconButton
          label="Rotate"
          tip="Rotate the 3D view around the port"
          pressed={rotating}
          onClick={camera.toggleRotate}
        >
          <Orbit aria-hidden="true" />
        </IconButton>
        <IconButton label="Reset" tip="Back to the overview" onClick={resetViews}>
          <RotateCcw aria-hidden="true" />
        </IconButton>
      </Group>
      <Group label="Show in 3D">
        <IconButton
          label="Cargo"
          tip="Show the cargo on pads, stacks and coils"
          pressed={cargo}
          onClick={() => setCargo(!cargo)}
        >
          <Package aria-hidden="true" />
        </IconButton>
        {containersEnabled() && (
          <IconButton
            label="Containers"
            tip="Show the container terminal"
            pressed={containers}
            onClick={() => setLayerVisible(CONTAINERS_LAYER, !containers)}
          >
            <Container aria-hidden="true" />
          </IconButton>
        )}
        <IconButton
          label="Labels"
          tip="Show the labels (L)"
          pressed={labels}
          onClick={() => setLabels(!labels)}
        >
          <Tag aria-hidden="true" />
        </IconButton>
        <LayersMenu />
      </Group>
      <span className="ml-auto" />
      <Button
        size="sm"
        variant="ghost"
        title="Hide or show the side panel (P)"
        onClick={() => setPanelHidden(!panelHidden)}
      >
        <PanelRight aria-hidden="true" className="size-4" />
        {panelHidden ? 'Show panel' : 'Hide panel'}
      </Button>
      {mode === 'monitor' && (
        <Button
          size="sm"
          variant="ghost"
          title="Fullscreen (F)"
          onClick={() => void toggleFullscreen()}
        >
          {fullscreen ? (
            <Minimize aria-hidden="true" className="size-4" />
          ) : (
            <Maximize aria-hidden="true" className="size-4" />
          )}
          {fullscreen ? 'Exit fullscreen' : 'Fullscreen'}
        </Button>
      )}
      <MoreMenu />
    </div>
  )
}
