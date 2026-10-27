import { useDraftLayout } from '@/lib/api'

/** The bottom strip of Design: which draft this is and who last changed it. */
export function DraftSummary() {
  const { data: draft } = useDraftLayout(undefined, true, { sync: true })
  if (!draft) return null
  const changed = draft.updatedAt
    ? ` · last change by ${draft.updatedBy ?? 'unknown'} at ${new Date(draft.updatedAt).toLocaleTimeString()}`
    : ''
  return <span>{`Draft v${draft.number} · ${draft.assets.length} assets${changed}`}</span>
}
