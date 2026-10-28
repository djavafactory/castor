import { AssetPalette, DragChip } from '@/features/design/asset-palette'
import { usePlacementGhost } from '@/features/design/place/use-placement'
import { CheckAndSaveKey, DesignInspector } from '@/features/design/inspector/inspector'
import { IssuesTab } from '@/features/design/issues/issues-tab'
import { useDraftValidation } from '@/features/design/issues/use-draft-validation'
import { VersionsTab } from '@/features/design/versions/versions-tab'
import { X } from 'lucide-react'
import { CountPill } from '@/components/ui/badge'
import { IconButton } from '@/components/ui/button'
import { Map2D } from '@/components/map/map-2d'
import { Tabs } from '@/components/ui/tabs'
import { LayoutAssetDetails } from '@/components/scene/layout/asset-details'
import { BuildingDetails } from '@/components/scene/buildings/building-details'
import { PortScene } from '@/components/scene/port-scene'
import { CatalogueTab } from './catalogue-tab'
import { PortDetails } from './port-details'
import { StatusStrip } from './status-strip'
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

/** Keeps the placement ghost current and shows the chip of a dragged palette tile; only Design places assets. */
function PlacementHost() {
  usePlacementGhost()
  return <DragChip />
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
    <div className="flex min-h-0 min-w-0 flex-col">
      {/* relative: below 1024 px the right panel is a drawer pinned to this row */}
      <div className="relative flex min-h-0 flex-1">
        {mode === 'design' && <PlacementHost />}
        {mode === 'design' && <CheckAndSaveKey />}
        {mode === 'design' && !paletteHidden && (
          <aside
            aria-label="Asset palette"
            className="w-[260px] shrink-0 overflow-auto border-r border-line-soft bg-paper p-3.5 max-wide:w-[220px] max-wide:p-3"
          >
            <h2 className="font-brand text-[11px] font-bold tracking-[0.07em] text-muted uppercase">
              Assets
            </h2>
            <AssetPalette />
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
      {mode === 'design' && <StatusStrip />}
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
  const setPanelHidden = useUiStore((s) => s.setPanelHidden)

  // 348 px; 300 px at <= 1100 px; below 1024 px a drawer over the stage, min(340 px, 92vw) wide
  return (
    <aside
      aria-label="Right panel"
      data-testid="right-panel"
      className="flex w-[348px] shrink-0 flex-col border-l border-line-soft bg-paper max-wide:w-[300px] max-lg:absolute max-lg:inset-y-0 max-lg:right-0 max-lg:z-overlay max-lg:w-[min(340px,92vw)] max-lg:shadow-popover"
    >
      <div className="flex items-end border-b border-line-soft">
        <Tabs
          panel
          label="Panel"
          value={active}
          tabs={tabs}
          onChange={setRightTab}
          className="min-w-0 flex-1 border-b-0"
        />
        <IconButton
          label="Close panel"
          tip="Close the panel (P)"
          className="mr-2 mb-1 hidden max-lg:inline-flex"
          onClick={() => setPanelHidden(true)}
        >
          <X aria-hidden="true" />
        </IconButton>
      </div>
      <div
        role="tabpanel"
        id={`tabpanel-${active}`}
        aria-labelledby={`tab-${active}`}
        tabIndex={0}
        className="min-h-0 flex-1 overflow-auto p-3.5"
      >
        {active === 'details' && <SelectionDetails />}
        {active === 'inspector' && <DesignInspector />}
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
