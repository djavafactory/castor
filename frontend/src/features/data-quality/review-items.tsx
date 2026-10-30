import { useState } from 'react'
import { QueryBoundary } from '@/components/states/query-boundary'
import { EmptyState } from '@/components/states/states'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DataTable, type Column } from '@/components/ui/card'
import { Input, Label, Select } from '@/components/ui/field'
import {
  RECONCILIATION_KINDS,
  useOpenReconciliationItems,
  type ReconciliationItem,
} from '@/lib/api'
import { shortId } from '@/features/integrations/integrations-text'
import { KIND_HELP, KIND_LABEL, ageText, detailText, entityText } from './data-quality-text'
import { ResolveDrawer } from './resolve-drawer'
import { useNowMs } from './use-now'

/**
 * What the projections could not settle alone, oldest trouble first to read: stale events, conflicts between sources,
 * balances that would go below zero and stock above capacity. An operator resolves an item with a note.
 */
export function ReviewItems() {
  const [kind, setKind] = useState('')
  const [text, setText] = useState('')
  const [resolving, setResolving] = useState<ReconciliationItem | null>(null)
  const query = useOpenReconciliationItems(kind)
  const now = useNowMs()

  const needle = text.trim().toLowerCase()
  const matches = (item: ReconciliationItem) =>
    needle === '' ||
    [
      item.entityType,
      item.entityId,
      item.eventType ?? '',
      item.eventId ?? '',
      detailText(item),
    ].some((field) => field.toLowerCase().includes(needle))

  return (
    <section aria-labelledby="review-items-heading" className="flex flex-col gap-3">
      <h2 id="review-items-heading" className="text-sm font-bold">
        Review items
      </h2>
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1">
          <Label htmlFor="review-kind">Kind</Label>
          <Select id="review-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
            <option value="">All</option>
            {RECONCILIATION_KINDS.map((k) => (
              <option key={k} value={k}>
                {KIND_LABEL[k]}
              </option>
            ))}
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="review-text">Find</Label>
          <Input
            id="review-text"
            value={text}
            placeholder="Entity, event or detail"
            onChange={(e) => setText(e.target.value)}
          />
        </div>
      </div>
      <QueryBoundary query={query}>
        {(items) => {
          const rows = items.filter(matches)
          return rows.length === 0 ? (
            <EmptyState>
              {kind !== '' || needle !== ''
                ? 'No open item matches the filter.'
                : 'Nothing needs review. Every event was applied without a conflict.'}
            </EmptyState>
          ) : (
            <DataTable
              caption="Open review items, newest first"
              rows={rows}
              rowKey={(i) => String(i.id)}
              columns={columns(now, setResolving)}
            />
          )
        }}
      </QueryBoundary>
      {resolving !== null && (
        <ResolveDrawer key={resolving.id} item={resolving} onClose={() => setResolving(null)} />
      )}
    </section>
  )
}

function columns(
  now: number,
  onResolve: (item: ReconciliationItem) => void,
): Column<ReconciliationItem>[] {
  return [
    {
      key: 'kind',
      header: 'Kind',
      render: (i) => (
        <Badge variant="warn" title={KIND_HELP[i.kind]}>
          {KIND_LABEL[i.kind]}
        </Badge>
      ),
    },
    {
      key: 'entity',
      header: 'Entity',
      render: (i) => (
        <span className="font-mono" title={`${i.entityType} ${i.entityId}`}>
          {entityText(i.entityType, i.entityId)}
        </span>
      ),
    },
    {
      key: 'event',
      header: 'Event',
      render: (i) => (
        <span className="inline-flex flex-col">
          <span className="font-mono">{i.eventType ?? 'unknown type'}</span>
          {i.eventId && (
            <span className="font-mono text-[11px] text-muted" title={i.eventId}>
              {shortId(i.eventId)}
            </span>
          )}
        </span>
      ),
    },
    { key: 'detail', header: 'Detail', render: (i) => detailText(i) },
    { key: 'age', header: 'Age', render: (i) => ageText(i.createdAt, now) },
    {
      key: 'action',
      header: 'Action',
      render: (i) => (
        <Button aria-label={`Resolve item ${i.id}`} onClick={() => onResolve(i)}>
          Resolve
        </Button>
      ),
    },
  ]
}
