import { useState } from 'react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { KeyValue, SectionHeading } from '@/components/ui/card'
import { Drawer } from '@/components/ui/drawer'
import { Label } from '@/components/ui/field'
import { useResolveReconciliationItem, type ReconciliationItem } from '@/lib/api'
import { formatWhen } from '@/features/integrations/integrations-text'
import { problemText } from '@/features/integrations/problem-text'
import { JsonView } from '@/features/integrations/json-view'
import { KIND_HELP, KIND_LABEL, detailText } from './data-quality-text'

const MAX_NOTE = 2000

/**
 * One review item with everything the rule saw, and the note that closes it. Resolving records who decided and why; it
 * changes no balance, call, lot or visit. A balance is corrected by an inventory adjustment, which is a separate act.
 */
export function ResolveDrawer({
  item,
  onClose,
}: {
  item: ReconciliationItem
  onClose: () => void
}) {
  const resolve = useResolveReconciliationItem()
  const [note, setNote] = useState('')
  const trimmed = note.trim()

  function submit() {
    resolve.mutate(
      { id: item.id, note: trimmed },
      {
        onSuccess: () => {
          toast('Item resolved')
          onClose()
        },
      },
    )
  }

  return (
    <Drawer open title={`Resolve item ${item.id}`} onClose={onClose}>
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant="warn">{KIND_LABEL[item.kind]}</Badge>
        <span className="text-xs text-muted">{KIND_HELP[item.kind]}</span>
      </div>
      <p className="mt-2 text-[13px] break-words">{detailText(item)}</p>

      <KeyValue
        className="mt-3"
        items={[
          ['Entity', `${item.entityType} ${item.entityId}`],
          ['Event', `${item.eventType ?? 'unknown type'} ${item.eventId ?? ''}`.trim()],
          ['Opened', formatWhen(item.createdAt)],
        ]}
      />

      <SectionHeading>What the rule saw</SectionHeading>
      <JsonView value={item.detail} label="Detail of the item" />

      <SectionHeading>Your decision</SectionHeading>
      <p className="text-xs text-muted">
        Resolving records your decision and this note in the audit trail. It does not change any
        data: to correct a balance, an inventory adjustment is sent as its own event.
      </p>
      <form
        className="mt-2 flex flex-col gap-2"
        onSubmit={(event) => {
          event.preventDefault()
          if (trimmed !== '') submit()
        }}
      >
        <Label htmlFor="resolve-note">Note</Label>
        <textarea
          id="resolve-note"
          value={note}
          maxLength={MAX_NOTE}
          rows={4}
          required
          aria-invalid={resolve.isError}
          placeholder="Why this needs no more action, or what was done about it"
          className="w-full rounded-md border border-line bg-white px-2 py-[5px] text-[13px] text-ink placeholder:text-muted"
          onChange={(event) => setNote(event.target.value)}
        />
        {resolve.isError && (
          <p role="alert" className="text-xs text-bad">
            {problemText(resolve.error)}
          </p>
        )}
        <div className="flex gap-2">
          <Button type="submit" variant="primary" disabled={trimmed === '' || resolve.isPending}>
            {resolve.isPending ? 'Resolving...' : 'Resolve'}
          </Button>
          <Button type="button" onClick={onClose}>
            Cancel
          </Button>
        </div>
      </form>
    </Drawer>
  )
}
