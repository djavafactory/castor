import { useUiStore, type ViewMode } from '@/lib/stores/ui-store'

const VIEW_MODES: { value: ViewMode; label: string }[] = [
  { value: '2d', label: '2D' },
  { value: '3d', label: '3D' },
  { value: 'split', label: 'Split' },
]

/**
 * The strip under the app bar. It hosts the controls shared by both modes (REPLICA-07) and the Design controls
 * (DSN tickets); those tickets add their buttons here.
 */
export function ToolStrip() {
  const mode = useUiStore((s) => s.mode)
  const viewMode = useUiStore((s) => s.viewMode)
  const setViewMode = useUiStore((s) => s.setViewMode)

  return (
    <div
      role="toolbar"
      aria-label="Tools"
      className="flex items-center gap-2 border-b border-line-soft bg-paper-2 px-4"
    >
      {mode === 'design' && (
        <div role="group" aria-label="View" className="inline-flex gap-0.5">
          {VIEW_MODES.map((v) => (
            <button
              key={v.value}
              type="button"
              aria-pressed={viewMode === v.value}
              className={`h-7 rounded-md px-2.5 text-xs font-medium ${
                viewMode === v.value ? 'bg-ink text-paper' : 'text-ink hover:bg-paper'
              }`}
              onClick={() => setViewMode(v.value)}
            >
              {v.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
