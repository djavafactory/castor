import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useDraftLayout } from '@/lib/api'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore } from '@/lib/stores/ui-store'

export const LAYOUT_STATUS_TIP = 'State of the layout you are editing'

/** What the editor is working on and whether it is saved. Only shown in Design. */
export function LayoutStatusBadge() {
  const mode = useUiStore((s) => s.mode)
  const pending = useEditorStore((s) => s.pendingSaves.length > 0)
  const failed = useEditorStore((s) => s.failedSaves.length > 0)
  const retry = useEditorStore((s) => s.retryFailedSaves)
  const { data: draft } = useDraftLayout(undefined, mode === 'design', { sync: true })

  if (mode !== 'design') return null
  if (pending) return <Pill variant="synthetic">Saving...</Pill>
  if (failed) {
    return (
      <span className="inline-flex items-center gap-1.5">
        <Pill variant="bad">Save failed</Pill>
        <Button size="sm" onClick={retry}>
          Retry
        </Button>
      </span>
    )
  }
  if (!draft) return null
  return draft.basedOnVersionNo == null ? (
    <Pill variant="neutral">Blank draft</Pill>
  ) : (
    <Pill variant="neutral">{`Draft (based on v${draft.basedOnVersionNo})`}</Pill>
  )
}

function Pill({
  variant,
  children,
}: {
  variant: 'synthetic' | 'bad' | 'neutral'
  children: string
}) {
  return (
    <Badge variant={variant} role="status" tabIndex={0} title={LAYOUT_STATUS_TIP}>
      {children}
    </Badge>
  )
}
