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
  Redo2,
  RotateCcw,
  Tag,
  Undo2,
  ZoomIn,
  ZoomOut,
} from 'lucide-react'
import type { ReactNode } from 'react'
import { Button, IconButton } from '@/components/ui/button'
import { PUBLISH_TIP } from '@/features/design/versions/versions-text'
import { Menu, MenuContent, MenuItem, MenuTrigger } from '@/components/ui/overlays'
import { Segmented } from '@/components/ui/segmented'
import { CONTAINERS_LAYER } from '@/components/scene/layers'
import { useMapStore } from '@/components/map/map-store'
import { PRESETS, presetByKey } from '@/components/scene/stage/camera-presets'
import { camera, useCameraStore } from '@/components/scene/stage/view-state'
import { containersEnabled } from '@/lib/features'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore, type ViewMode } from '@/lib/stores/ui-store'
import { runRedo, runUndo } from '@/features/design/history/undo-redo'
import { toggleFullscreen, useFullscreen } from './fullscreen'
import { LayersMenu } from './layers-menu'
import { MoreMenu } from './more-menu'
import { showsMap, showsScene } from './view-modes'

/** Undo and redo (DESIGNER-08): disabled with nothing to undo or redo. Design mode only. */
function UndoRedo() {
  const canUndo = useEditorStore((s) => s.undoStack.length > 0)
  const canRedo = useEditorStore((s) => s.redoStack.length > 0)
  return (
    <Group label="Undo and redo">
      <IconButton label="Undo" tip="Undo (Ctrl+Z)" disabled={!canUndo} onClick={runUndo}>
        <Undo2 aria-hidden="true" />
      </IconButton>
      <IconButton label="Redo" tip="Redo (Ctrl+Y)" disabled={!canRedo} onClick={runRedo}>
        <Redo2 aria-hidden="true" />
      </IconButton>
    </Group>
  )
}

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

/** The text of an icon + text tool: at <= 1100 px only the icon shows, the text stays the name for screen readers. */
function ToolLabel({ children }: { children: ReactNode }) {
  return <span className="max-wide:sr-only">{children}</span>
}

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
          <ToolLabel>{label}</ToolLabel>
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
  const setRightTab = useUiStore((s) => s.setRightTab)
  const rotating = useCameraStore((s) => s.autoRotate)
  const fullscreen = useFullscreen()

  return (
    <div
      role="toolbar"
      aria-label="Tools"
      className="relative z-strip flex items-center gap-2 border-b border-line-soft bg-paper-2 px-4 max-wide:gap-1.5 max-wide:px-3"
    >
      {mode === 'design' && (
        <>
          <button
            type="button"
            aria-pressed={!paletteHidden}
            title="Show or hide the asset menu (A)"
            onClick={() => setPaletteHidden(!paletteHidden)}
            className="inline-flex h-7 items-center gap-1.5 rounded-lg px-2 text-[13px] font-medium hover:bg-paper aria-pressed:bg-signal-soft"
          >
            <PanelLeft aria-hidden="true" className="size-4" />
            <ToolLabel>Assets</ToolLabel>
          </button>
          <Segmented label="View" value={viewMode} options={VIEW_MODES} onChange={setViewMode} />
          <UndoRedo />
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
      {mode === 'design' && (
        <>
          <Button
            size="sm"
            title="Check and save (Ctrl+S)"
            onClick={() => useEditorStore.getState().requestSave()}
          >
            Save draft
          </Button>
          <Button
            size="sm"
            variant="primary"
            title={PUBLISH_TIP}
            onClick={() => {
              setPanelHidden(false)
              setRightTab('versions')
            }}
          >
            Publish
          </Button>
        </>
      )}
      <Button
        size="sm"
        variant="ghost"
        title="Hide or show the side panel (P)"
        onClick={() => setPanelHidden(!panelHidden)}
      >
        <PanelRight aria-hidden="true" className="size-4" />
        <ToolLabel>{panelHidden ? 'Show panel' : 'Hide panel'}</ToolLabel>
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
          <ToolLabel>{fullscreen ? 'Exit fullscreen' : 'Fullscreen'}</ToolLabel>
        </Button>
      )}
      <MoreMenu />
    </div>
  )
}
