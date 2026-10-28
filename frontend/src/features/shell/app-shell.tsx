import { NewVersionWatcher } from './published-version'
import { useUiStore, type Mode } from '@/lib/stores/ui-store'
import { AppBar } from './app-bar'
import { DESIGN_NARROW_NOTICE, NARROW_NOTICE } from './screen-text'
import { ToolStrip } from './tool-strip'
import { WorkArea } from './work-area'

/** Below 1024 px only (CSS): Castor is a desktop tool; in Design, editing needs the room. */
function NarrowNotice({ mode }: { mode: Mode }) {
  return (
    <p
      role="note"
      data-testid="narrow-notice"
      className="hidden border-b border-line-soft bg-warn-soft px-4 py-1.5 text-[13px] text-warn-soft-fg max-lg:block"
    >
      {NARROW_NOTICE}
      {mode === 'design' && ` ${DESIGN_NARROW_NOTICE}`}
    </p>
  )
}

/**
 * The frame both modes share: app bar (56 px), tool strip (40 px), the narrow-screen notice (below 1024 px only) and
 * the work area (rest). The page never scrolls.
 */
export function AppShell() {
  const mode = useUiStore((s) => s.mode)
  return (
    <div className="grid h-full grid-rows-[56px_40px_auto_minmax(0,1fr)] overflow-hidden bg-paper">
      <AppBar />
      <ToolStrip />
      <NarrowNotice mode={mode} />
      <WorkArea />
      {mode === 'monitor' && <NewVersionWatcher />}
    </div>
  )
}
