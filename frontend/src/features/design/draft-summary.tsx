import { useDraftLayout } from '@/lib/api'

/** The bottom strip of Design: which draft this is and who last changed it. */
export function DraftSummary() {
  const { data: draft } = useDraftLayout(undefined, true, { sync: true })
  if (!draft) return null
  const changed = draft.updatedAt
    ? ` · last change by ${draft.updatedBy ?? 'unknown'} at ${new Date(draft.updatedAt).toLocaleTimeString()}`
    : ''
  const text = `Draft v${draft.number} · ${draft.assets.length} assets${changed}`
  // The bottom strip may cut it short; the tooltip has it whole
  return <span title={text}>{text}</span>
}
