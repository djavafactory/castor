import { CircleAlert, Ruler } from 'lucide-react'
import { CursorReadout } from '@/components/map/cursor-readout'
import { DraftSummary } from '@/features/design/draft-summary'
import { HistoryPopover } from '@/features/design/history/history-popover'
import { useDraftValidation } from '@/features/design/issues/use-draft-validation'
import { MEASURE_TIP, toggleMeasure } from '@/features/design/measure/measure'
import { usePort } from '@/lib/api'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { cn } from '@/lib/utils'
import { BasemapSelect } from './basemap-select'
import { ACCURACY_TIP, crsTip, ENVELOPE_TIP, issuesText } from './status-text'

function Divider() {
  return <span aria-hidden="true" className="h-4 w-px shrink-0 bg-line-soft" />
}

function Check({
  label,
  tip,
  checked,
  onChange,
}: {
  label: string
  tip: string
  checked: boolean
  onChange: (on: boolean) => void
}) {
  return (
    <label
      title={tip}
      className="flex shrink-0 cursor-pointer items-center gap-1.5 rounded-md px-1 hover:bg-paper-2"
    >
      <input
        type="checkbox"
        className="size-3.5 accent-signal"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
      />
      {label}
    </label>
  )
}

function Crs() {
  const port = usePort()
  return (
    <span title={crsTip(port.data?.measureSrid ?? 3380)} className="shrink-0 cursor-help">
      <span className="text-xs text-muted">CRS</span> WGS84 / metres
    </span>
  )
}

function MeasureButton() {
  const on = useEditorStore((s) => s.tool?.kind === 'measure')
  return (
    <button
      type="button"
      aria-pressed={on}
      title={MEASURE_TIP}
      onClick={toggleMeasure}
      className="inline-flex h-6 shrink-0 items-center gap-1 rounded-md border border-transparent px-1.5 hover:bg-paper-2 aria-pressed:border-signal aria-pressed:bg-signal-soft aria-pressed:text-signal"
    >
      <Ruler aria-hidden="true" className="size-3.5" />
      Measure
    </button>
  )
}

/** Errors and warnings of the draft; red with errors. Opens the Issues tab. */
function IssuesButton() {
  const { query } = useDraftValidation()
  const setPanelHidden = useUiStore((s) => s.setPanelHidden)
  const setRightTab = useUiStore((s) => s.setRightTab)
  const errors = query.data?.errors ?? 0
  const warnings = query.data?.warnings ?? 0
  return (
    <button
      type="button"
      title="Open the Issues tab"
      onClick={() => {
        setPanelHidden(false)
        setRightTab('issues')
      }}
      className={cn(
        'inline-flex h-6 shrink-0 items-center gap-1 rounded-md px-1.5 hover:bg-paper-2',
        errors > 0 && 'bg-bad-soft font-semibold text-bad hover:bg-bad-soft',
      )}
    >
      {errors > 0 && <CircleAlert aria-hidden="true" className="size-3.5" />}
      {query.data ? issuesText(errors, warnings) : 'Checking...'}
    </button>
  )
}

/**
 * The bottom strip of Design, left to right: cursor, CRS, basemap, Measure, Accuracy, Envelope | draft, History,
 * issues. The draft summary takes what room is left and is cut first.
 */
export function StatusStrip() {
  const accuracy = useUiStore((s) => s.accuracyColouring)
  const setAccuracy = useUiStore((s) => s.setAccuracyColouring)
  const envelope = useUiStore((s) => s.envelope)
  const setEnvelope = useUiStore((s) => s.setEnvelope)
  return (
    <footer
      aria-label="Status"
      className="flex h-8 shrink-0 items-center gap-2.5 border-t border-line-soft bg-paper px-4 text-[13px] whitespace-nowrap text-ink"
    >
      <span className="shrink-0 font-mono text-xs">
        <CursorReadout />
      </span>
      <Divider />
      <Crs />
      <BasemapSelect />
      <Divider />
      <MeasureButton />
      <Check label="Accuracy" tip={ACCURACY_TIP} checked={accuracy} onChange={setAccuracy} />
      <Check label="Envelope" tip={ENVELOPE_TIP} checked={envelope} onChange={setEnvelope} />
      <span className="ml-auto min-w-0 truncate pl-2 font-mono text-xs text-muted">
        <DraftSummary />
      </span>
      <HistoryPopover />
      <IssuesButton />
    </footer>
  )
}
