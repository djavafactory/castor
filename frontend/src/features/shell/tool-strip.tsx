import { PanelRight, PanelLeft, Tag } from 'lucide-react'
import { IconButton } from '@/components/ui/button'
import { Segmented } from '@/components/ui/segmented'
import { useUiStore, type ViewMode } from '@/lib/stores/ui-store'
import { LayersMenu } from './layers-menu'

const VIEW_MODES: { value: ViewMode; label: string }[] = [
  { value: '2d', label: '2D' },
  { value: '3d', label: '3D' },
  { value: 'split', label: 'Split' },
]

/**
 * The strip under the app bar. It hosts the controls shared by both modes (REPLICA-07) and the Design controls
 * (DSN tickets); those tickets add their buttons here. Nothing floats over it (stage overlays sit below it).
 */
export function ToolStrip() {
  const mode = useUiStore((s) => s.mode)
  const viewMode = useUiStore((s) => s.viewMode)
  const setViewMode = useUiStore((s) => s.setViewMode)
  const panelHidden = useUiStore((s) => s.panelHidden)
  const setPanelHidden = useUiStore((s) => s.setPanelHidden)
  const labels = useUiStore((s) => s.labels)
  const setLabels = useUiStore((s) => s.setLabels)
  const paletteHidden = useUiStore((s) => s.paletteHidden)
  const setPaletteHidden = useUiStore((s) => s.setPaletteHidden)

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
            pressed={paletteHidden}
            onClick={() => setPaletteHidden(!paletteHidden)}
          >
            <PanelLeft aria-hidden="true" />
          </IconButton>
        </>
      )}
      <LayersMenu />
      <IconButton label="Labels (L)" pressed={labels} onClick={() => setLabels(!labels)}>
        <Tag aria-hidden="true" />
      </IconButton>
      <IconButton
        label="Hide panel"
        pressed={panelHidden}
        className="ml-auto"
        onClick={() => setPanelHidden(!panelHidden)}
      >
        <PanelRight aria-hidden="true" />
      </IconButton>
    </div>
  )
}
