import { NewVersionWatcher } from './published-version'
import { useUiStore } from '@/lib/stores/ui-store'
import { AppBar } from './app-bar'
import { ToolStrip } from './tool-strip'
import { WorkArea } from './work-area'

/** The frame both modes share: app bar (56 px), tool strip (40 px), work area (rest). The page never scrolls. */
export function AppShell() {
  const mode = useUiStore((s) => s.mode)
  return (
    <div className="grid h-full grid-rows-[56px_40px_minmax(0,1fr)] overflow-hidden bg-paper">
      <AppBar />
      <ToolStrip />
      <WorkArea />
      {mode === 'monitor' && <NewVersionWatcher />}
    </div>
  )
}
