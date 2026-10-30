import { useState } from 'react'
import { QueryBoundary } from '@/components/states/query-boundary'
import { EmptyState } from '@/components/states/states'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DataTable, type Column } from '@/components/ui/card'
import { Input, Label, Select } from '@/components/ui/field'
import {
  INTEGRATION_SOURCES,
  useQuarantine,
  type QuarantineEntry,
  type UnresolvedId,
} from '@/lib/api'
import { REASON_CODES, formatWhen, shortId } from './integrations-text'
import { QuarantineDrawer } from './quarantine-drawer'

export interface QuarantineFilterState {
  source: string
  reason: string
}

/**
 * What the gateway refused and why, newest first. A replay that was refused again is its own row, linked to the original
 * it repeats; nothing here is ever removed or changed by a replay.
 */
export function QuarantineTab({
  filter,
  onFilterChange,
  canAdminister,
  onAddMapping,
}: {
  filter: QuarantineFilterState
  onFilterChange: (filter: QuarantineFilterState) => void
  canAdminister: boolean
  onAddMapping: (id: Pick<UnresolvedId, 'source' | 'entityType' | 'sourceId'>) => void
}) {
  const query = useQuarantine({ source: filter.source, reason: filter.reason })
  const [text, setText] = useState('')
  const [open, setOpen] = useState<string | null>(null)

  const entries = query.data?.pages.flatMap((page) => page.items) ?? []
  const needle = text.trim().toLowerCase()
  const rows = entries.filter(
    (e) =>
      needle === '' ||
      e.sourceEventId.toLowerCase().includes(needle) ||
      e.detail.toLowerCase().includes(needle),
  )
  const filtered = filter.source !== '' || filter.reason !== '' || needle !== ''

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1">
          <Label htmlFor="quarantine-source">Source</Label>
          <Select
            id="quarantine-source"
            value={filter.source}
            onChange={(e) => onFilterChange({ ...filter, source: e.target.value })}
          >
            <option value="">All</option>
            {INTEGRATION_SOURCES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="quarantine-reason">Reason</Label>
          <Select
            id="quarantine-reason"
            value={filter.reason}
            onChange={(e) => onFilterChange({ ...filter, reason: e.target.value })}
          >
            <option value="">All</option>
            {REASON_CODES.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="quarantine-text">Find</Label>
          <Input
            id="quarantine-text"
            value={text}
            placeholder="Source event id or detail"
            onChange={(e) => setText(e.target.value)}
          />
        </div>
      </div>
      <QueryBoundary query={query}>
        {() =>
          rows.length === 0 ? (
            <EmptyState>
              {filtered
                ? 'No quarantined record matches the filter.'
                : 'Nothing is quarantined. Every record the sources sent was accepted.'}
            </EmptyState>
          ) : (
            <>
              <DataTable
                caption="Quarantined records, newest first"
                rows={rows}
                rowKey={(e) => String(e.id)}
                columns={columns(setOpen)}
              />
              {query.hasNextPage && (
                <Button
                  className="self-start"
                  disabled={query.isFetchingNextPage}
                  onClick={() => void query.fetchNextPage()}
                >
                  {query.isFetchingNextPage ? 'Loading...' : 'Load older records'}
                </Button>
              )}
            </>
          )
        }
      </QueryBoundary>
      {open !== null && (
        <QuarantineDrawer
          key={open}
          receptionId={open}
          canAdminister={canAdminister}
          onOpen={setOpen}
          onAddMapping={onAddMapping}
          onClose={() => setOpen(null)}
        />
      )}
    </div>
  )
}

function replayCell(e: QuarantineEntry) {
  if (e.replayOf) return <Badge variant="neutral">Replay, refused again</Badge>
  if (e.replays === 0 || !e.latestReplayStatus)
    return <span className="text-muted">Not replayed</span>
  const label =
    e.latestReplayStatus === 'NORMALIZED'
      ? 'Replayed, accepted'
      : e.latestReplayStatus === 'RECEIVED'
        ? 'Replay waiting'
        : 'Replayed, refused again'
  return (
    <Badge variant={e.latestReplayStatus === 'NORMALIZED' ? 'ok' : 'neutral'}>
      {label}
      {e.replays > 1 ? ` (${e.replays})` : ''}
    </Badge>
  )
}

function columns(onOpen: (receptionId: string) => void): Column<QuarantineEntry>[] {
  return [
    { key: 'at', header: 'Quarantined', render: (e) => formatWhen(e.quarantinedAt) },
    {
      key: 'source',
      header: 'Source',
      render: (e) => <span className="font-mono">{e.source}</span>,
    },
    {
      key: 'event',
      header: 'Source event id',
      render: (e) => (
        <button
          type="button"
          className="max-w-[220px] truncate text-left font-mono underline"
          title={e.receptionId}
          aria-label={`Open record ${e.sourceEventId} (${shortId(e.receptionId)})`}
          onClick={() => onOpen(e.receptionId)}
        >
          {e.sourceEventId}
        </button>
      ),
    },
    {
      key: 'reason',
      header: 'Reason',
      render: (e) => (
        <span className="inline-flex flex-col">
          <Badge variant="bad">{e.reasonCode}</Badge>
          {e.subjectId && <span className="mt-0.5 font-mono text-[11px]">{e.subjectId}</span>}
        </span>
      ),
    },
    { key: 'replay', header: 'Replay', render: replayCell },
  ]
}
