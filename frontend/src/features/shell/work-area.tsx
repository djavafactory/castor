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
  return (
    <div className="flex min-h-0">
      {mode === 'design' && (
        <aside
          aria-label="Asset palette"
          className="w-60 shrink-0 border-r border-line-soft bg-paper p-3"
        >
          <h2 className="text-xs font-semibold tracking-wide text-muted uppercase">Assets</h2>
        </aside>
      )}
      <main
        aria-label={mode === 'design' ? `Design stage, ${viewMode}` : 'Port stage'}
        className="min-w-0 flex-1 bg-canvas"
      />
      <RightPanel mode={mode} />
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
      <div
        role="tablist"
        aria-label="Panel"
        className="flex gap-1 border-b border-line-soft px-3 pt-2"
      >
        {tabs.map((t) => (
          <button
            key={t.value}
            id={`tab-${t.value}`}
            type="button"
            role="tab"
            aria-selected={active === t.value}
            aria-controls={`tabpanel-${t.value}`}
            tabIndex={active === t.value ? 0 : -1}
            className={`h-8 rounded-t-md px-3 text-sm font-medium ${
              active === t.value ? 'border-b-2 border-signal text-ink' : 'text-muted hover:text-ink'
            }`}
            onClick={() => setRightTab(t.value)}
            onKeyDown={(e) => {
              const delta = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0
              if (!delta) return
              const next =
                tabs[
                  (tabs.findIndex((x) => x.value === t.value) + delta + tabs.length) % tabs.length
                ]
              setRightTab(next.value)
              document.getElementById(`tab-${next.value}`)?.focus()
            }}
          >
            {t.label}
          </button>
        ))}
      </div>
      <div
        role="tabpanel"
        id={`tabpanel-${active}`}
        aria-labelledby={`tab-${active}`}
        tabIndex={0}
        className="min-h-0 flex-1 overflow-auto p-3"
      />
    </aside>
  )
}
