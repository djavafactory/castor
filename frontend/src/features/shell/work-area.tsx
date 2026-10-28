import { DrawTypes } from '@/features/design/draw/draw-types'
import { DraftSummary } from '@/features/design/draft-summary'
import { IssuesTab } from '@/features/design/issues/issues-tab'
import { useDraftValidation } from '@/features/design/issues/use-draft-validation'
import { VersionsTab } from '@/features/design/versions/versions-tab'
import { CountPill } from '@/components/ui/badge'
import { CursorReadout } from '@/components/map/cursor-readout'
import { Map2D } from '@/components/map/map-2d'
import { Tabs } from '@/components/ui/tabs'
import { LayoutAssetDetails } from '@/components/scene/layout/asset-details'
import { BuildingDetails } from '@/components/scene/buildings/building-details'
import { ModelPalette } from '@/components/scene/models/model-palette'
import { PortScene } from '@/components/scene/port-scene'
import { BasemapSelect } from './basemap-select'
import { CatalogueTab } from './catalogue-tab'
import { DebugSwitches } from './more-menu'
import { PortDetails } from './port-details'
import { useSceneKeys } from './use-scene-keys'
import { showsMap, showsScene } from './view-modes'
import { useEffect, type ReactNode } from 'react'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore, type Mode, type RightTab } from '@/lib/stores/ui-store'

const TABS: Record<Mode, { value: RightTab; label: string; badge?: ReactNode }[]> = {
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
  useSceneKeys()
  // Monitor is always the 3D replica; Design shows the 2D map in the 2D and Split views and the replica in 3D and Split.
  const showScene = showsScene(mode, viewMode)
  const showMap = showsMap(mode, viewMode)
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
            <DrawTypes />
            <ModelPalette />
          </aside>
        )}
        <main
          aria-label={mode === 'design' ? `Design stage, ${viewMode}` : 'Port stage'}
          className="flex min-w-0 flex-1 bg-canvas"
        >
          {showMap && (
            <div className="relative min-w-0 flex-1 border-r border-line-soft last:border-r-0">
              <div className="absolute inset-0">
                <Map2D />
              </div>
            </div>
          )}
          {showScene && (
            <div className="relative min-w-0 flex-1">
              <div className="absolute inset-0">
                <PortScene />
              </div>
            </div>
          )}
        </main>
        {!panelHidden && <RightPanel mode={mode} />}
      </div>
      {mode === 'design' && (
        <footer
          aria-label="Status"
          className="flex h-8 shrink-0 items-center gap-3 border-t border-line-soft bg-paper px-4 font-mono text-xs text-muted"
        >
          <DraftSummary />
          <span className="ml-auto flex items-center gap-3 font-sans text-[13px] text-ink">
            <CursorReadout />
            <BasemapSelect />
            <DebugSwitches compact />
          </span>
        </footer>
      )}
    </div>
  )
}

function RightPanel({ mode }: { mode: Mode }) {
  // Design: the Issues tab carries a red count of errors and warnings, hidden when there are none
  const { query: validation } = useDraftValidation(mode === 'design')
  const issueCount = (validation.data?.errors ?? 0) + (validation.data?.warnings ?? 0)
  const tabs = TABS[mode].map((t) =>
    t.value === 'issues' && issueCount > 0
      ? { ...t, badge: <CountPill tone="bad">{issueCount}</CountPill> }
      : t,
  )
  const stored = useUiStore((s) => s.rightTab)
  const setRightTab = useUiStore((s) => s.setRightTab)
  // The remembered tab may belong to the other mode; fall back to this mode's first tab.
  const selection = useSelectionStore((s) => s.selectedAssetId ?? s.selectedReferenceId)
  // Selecting anything brings its details forward, except from the Issues list: a row there selects its asset and the
  // list stays, so the designer can go from one problem to the next
  useEffect(() => {
    if (selection === null) return
    if (mode === 'design' && useUiStore.getState().rightTab === 'issues') return
    setRightTab(mode === 'monitor' ? 'details' : 'inspector')
  }, [selection, mode, setRightTab])
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
        {active === 'catalogue' && <CatalogueTab />}
        {active === 'issues' && <IssuesTab />}
        {active === 'versions' && <VersionsTab />}
      </div>
    </aside>
  )
}

/** Read-only details of what is selected: a layout asset or an OpenStreetMap building. */
function SelectionDetails() {
  const asset = useSelectionStore((s) => s.selectedAssetId)
  const reference = useSelectionStore((s) => s.selectedReferenceId)
  if (asset === null && reference === null) return <PortDetails />
  return (
    <>
      <LayoutAssetDetails />
      <BuildingDetails />
    </>
  )
}
