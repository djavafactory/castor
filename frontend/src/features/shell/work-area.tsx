import { DraftSummary } from '@/features/design/draft-summary'
import { Tabs } from '@/components/ui/tabs'
import { LayoutAssetDetails } from '@/components/scene/layout/asset-details'
import { BuildingDetails } from '@/components/scene/buildings/building-details'
import { ModelPalette } from '@/components/scene/models/model-palette'
import { PortScene } from '@/components/scene/port-scene'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore, type Mode, type RightTab } from '@/lib/stores/ui-store'

const TABS: Record<Mode, { value: RightTab; label: string }[]> = {
  monitor: [
    { value: 'details', label: 'Details' },
    { value: 'catalogue', label: 'Catalogue' },
  ],
  design: [
    { value: 'inspector', label: 'Inspector' },
    { value: 'issues', label: 'Issues' },
    { value: 'versions', label: 'Versions' },
  ],
}

/**
 * The work area of both modes. Monitor: stage + right panel. Design: asset palette + stage + right panel.
 * Features plug their content into the stage, palette and tab panels.
 */
export function WorkArea() {
  const mode = useUiStore((s) => s.mode)
  const viewMode = useUiStore((s) => s.viewMode)
  const panelHidden = useUiStore((s) => s.panelHidden)
  const paletteHidden = useUiStore((s) => s.paletteHidden)
  // Monitor is always the 3D replica; Design shows it in the 3D and Split views (the 2D map is a DSN ticket).
  const showScene = mode === 'monitor' || viewMode !== '2d'
  return (
    <div className="flex min-h-0 flex-col">
      <div className="flex min-h-0 flex-1">
        {mode === 'design' && !paletteHidden && (
          <aside
            aria-label="Asset palette"
            className="w-[260px] shrink-0 overflow-auto border-r border-line-soft bg-paper p-3.5"
          >
            <h2 className="font-brand text-[11px] font-bold tracking-[0.07em] text-muted uppercase">
              Assets
            </h2>
            <ModelPalette />
          </aside>
        )}
        <main
          aria-label={mode === 'design' ? `Design stage, ${viewMode}` : 'Port stage'}
          className="relative min-w-0 flex-1 bg-canvas"
        >
          {showScene && <PortScene />}
        </main>
        {!panelHidden && <RightPanel mode={mode} />}
      </div>
      {mode === 'design' && (
        <footer
          aria-label="Status"
          className="flex h-8 shrink-0 items-center gap-3 border-t border-line-soft bg-paper px-4 font-mono text-xs text-muted"
        >
          <DraftSummary />
        </footer>
      )}
    </div>
  )
}

function RightPanel({ mode }: { mode: Mode }) {
  const tabs = TABS[mode]
  const stored = useUiStore((s) => s.rightTab)
  const setRightTab = useUiStore((s) => s.setRightTab)
  // The remembered tab may belong to the other mode; fall back to this mode's first tab.
  const active = tabs.some((t) => t.value === stored) ? stored : tabs[0].value

  return (
    <aside
      aria-label="Right panel"
      className="flex w-[348px] shrink-0 flex-col border-l border-line-soft bg-paper"
    >
      <Tabs panel label="Panel" value={active} tabs={tabs} onChange={setRightTab} />
      <div
        role="tabpanel"
        id={`tabpanel-${active}`}
        aria-labelledby={`tab-${active}`}
        tabIndex={0}
        className="min-h-0 flex-1 overflow-auto p-3.5"
      >
        {(active === 'details' || active === 'inspector') && <SelectionDetails />}
      </div>
    </aside>
  )
}

/** Read-only details of what is selected: a layout asset or an OpenStreetMap building. */
function SelectionDetails() {
  const asset = useSelectionStore((s) => s.selectedAssetId)
  const reference = useSelectionStore((s) => s.selectedReferenceId)
  if (asset === null && reference === null) {
    return (
      <p className="text-[13px] text-muted">Select an asset in the 3D view to read its details.</p>
    )
  }
  return (
    <>
      <LayoutAssetDetails />
      <BuildingDetails />
    </>
  )
}
