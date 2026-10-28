import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/overlays'
import { useEditorStore } from '@/lib/stores/editor-store'

function hhmm(time: number): string {
  const d = new Date(time)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

/**
 * "History (N)" in the bottom strip of Design (DESIGNER-08): the last 60 edits, undo, redo, basemap changes and
 * measurements, newest first.
 */
export function HistoryPopover() {
  const log = useEditorStore((s) => s.historyLog)
  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          title="Edit history"
          className="h-6 rounded-md px-1.5 font-sans text-[13px] text-ink hover:bg-paper-2"
        >
          {`History (${log.length})`}
        </button>
      </PopoverTrigger>
      <PopoverContent
        align="end"
        heading={`History (${log.length})`}
        className="max-h-[70vh] overflow-auto"
      >
        {log.length === 0 ? (
          <p className="text-muted">No edits yet.</p>
        ) : (
          <ul className="flex flex-col gap-1 font-mono text-xs">
            {log.map((entry, i) => (
              <li key={i} className="flex gap-2">
                <span className="text-muted">{hhmm(entry.time)}</span>
                <span className="truncate font-sans text-[13px] text-ink">{entry.label}</span>
              </li>
            ))}
          </ul>
        )}
      </PopoverContent>
    </Popover>
  )
}
