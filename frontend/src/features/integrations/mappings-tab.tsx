import { useState } from 'react'
import { toast } from 'sonner'
import { QueryBoundary } from '@/components/states/query-boundary'
import { EmptyState } from '@/components/states/states'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DataTable, SectionHeading } from '@/components/ui/card'
import { Input, Label, Select } from '@/components/ui/field'
import { ConfirmDialog } from '@/components/ui/overlays'
import {
  MAPPING_ENTITY_TYPES,
  MAPPING_SOURCES,
  useCloseIdentityMapping,
  useIdentityMappings,
  useUnresolvedIds,
  type IdentityMapping,
  type MappingFilter,
  type UnresolvedId,
} from '@/lib/api'
import { formatWhen } from './integrations-text'
import { MappingForm, type MappingDraft } from './mapping-form'
import { problemText } from './problem-text'

/**
 * The identity mappings (which Castor asset a source's id stands for) and the source ids that quarantined records are
 * still waiting for. A mapping is closed, never deleted: events of an earlier time still map the way they did.
 */
export function MappingsTab({
  canAdminister,
  draft,
  onDraftChange,
  onShowQuarantine,
}: {
  canAdminister: boolean
  /** The open "new mapping" form: its starting values, or null when the form is closed. */
  draft: MappingDraft | null
  onDraftChange: (draft: MappingDraft | null) => void
  onShowQuarantine: (source: string) => void
}) {
  const [filter, setFilter] = useState<MappingFilter>({ state: 'open' })
  const mappings = useIdentityMappings(filter)
  const unresolved = useUnresolvedIds()
  const close = useCloseIdentityMapping()
  const [closing, setClosing] = useState<IdentityMapping | null>(null)

  const filtered = Boolean(filter.source || filter.entityType || filter.sourceId)

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center">
        <SectionHeading className="m-0">Source ids waiting for a mapping</SectionHeading>
        {canAdminister && (
          <Button
            variant="primary"
            className="ml-auto"
            onClick={() => onDraftChange({ source: 'cbas', entityType: 'BERTH', sourceId: '' })}
          >
            New mapping
          </Button>
        )}
      </div>
      <QueryBoundary query={unresolved}>
        {({ items }) =>
          items.length === 0 ? (
            <EmptyState>No source id is waiting for a mapping.</EmptyState>
          ) : (
            <DataTable
              caption="Source ids that quarantined records are waiting for"
              rows={items}
              rowKey={(u) => `${u.source}/${u.entityType}/${u.sourceId}/${u.reasonCode}`}
              columns={[
                { key: 'source', header: 'Source', render: (u) => u.source },
                { key: 'type', header: 'Kind', render: (u) => u.entityType },
                {
                  key: 'id',
                  header: 'Source id',
                  render: (u) => <span className="font-mono">{u.sourceId}</span>,
                },
                {
                  key: 'reason',
                  header: 'Reason',
                  render: (u) => <Badge variant="bad">{u.reasonCode}</Badge>,
                },
                {
                  key: 'records',
                  header: 'Records',
                  numeric: true,
                  render: (u) => (
                    <button
                      type="button"
                      className="font-semibold underline"
                      aria-label={`${u.records} quarantined records of ${u.sourceId}: show them`}
                      onClick={() => onShowQuarantine(u.source)}
                    >
                      {u.records}
                    </button>
                  ),
                },
                { key: 'seen', header: 'Last seen', render: (u) => formatWhen(u.lastSeenAt) },
                {
                  key: 'actions',
                  header: 'Actions',
                  render: (u: UnresolvedId) =>
                    canAdminister && u.reasonCode === 'UNKNOWN_MAPPING' ? (
                      <Button
                        size="sm"
                        aria-label={`Add a mapping for ${u.sourceId}`}
                        onClick={() =>
                          onDraftChange({
                            source: u.source,
                            entityType: u.entityType,
                            sourceId: u.sourceId,
                          })
                        }
                      >
                        Add mapping
                      </Button>
                    ) : null,
                },
              ]}
            />
          )
        }
      </QueryBoundary>

      <SectionHeading>Mappings</SectionHeading>
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1">
          <Label htmlFor="mappings-source">Source</Label>
          <Select
            id="mappings-source"
            value={filter.source ?? ''}
            onChange={(e) => setFilter({ ...filter, source: e.target.value })}
          >
            <option value="">All</option>
            {MAPPING_SOURCES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="mappings-type">Kind</Label>
          <Select
            id="mappings-type"
            value={filter.entityType ?? ''}
            onChange={(e) => setFilter({ ...filter, entityType: e.target.value })}
          >
            <option value="">All</option>
            {MAPPING_ENTITY_TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="mappings-state">State</Label>
          <Select
            id="mappings-state"
            value={filter.state ?? 'all'}
            onChange={(e) =>
              setFilter({ ...filter, state: e.target.value as NonNullable<MappingFilter['state']> })
            }
          >
            <option value="open">Open</option>
            <option value="closed">Closed</option>
            <option value="all">All</option>
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="mappings-id">Source id</Label>
          <Input
            id="mappings-id"
            value={filter.sourceId ?? ''}
            placeholder="Part of the id"
            onChange={(e) => setFilter({ ...filter, sourceId: e.target.value })}
          />
        </div>
      </div>
      <QueryBoundary query={mappings}>
        {({ items }) =>
          items.length === 0 ? (
            <EmptyState>
              {filtered || filter.state !== 'all'
                ? 'No mapping matches the filter.'
                : 'There is no identity mapping yet.'}
            </EmptyState>
          ) : (
            <DataTable
              caption="Identity mappings"
              rows={items}
              rowKey={(m) => String(m.id)}
              columns={[
                { key: 'source', header: 'Source', render: (m) => m.sourceSystem },
                { key: 'type', header: 'Kind', render: (m) => m.sourceEntityType },
                {
                  key: 'id',
                  header: 'Source id',
                  render: (m) => <span className="font-mono">{m.sourceId}</span>,
                },
                {
                  key: 'canonical',
                  header: 'Canonical id',
                  render: (m) => <span className="font-mono break-all">{m.canonicalId}</span>,
                },
                { key: 'from', header: 'Valid from', render: (m) => formatWhen(m.effectiveFrom) },
                {
                  key: 'to',
                  header: 'Valid until',
                  render: (m) => (m.effectiveTo ? formatWhen(m.effectiveTo) : 'Open'),
                },
                { key: 'by', header: 'Created by', render: (m) => m.createdBy },
                {
                  key: 'actions',
                  header: 'Actions',
                  render: (m) =>
                    canAdminister && !m.effectiveTo ? (
                      <Button
                        size="sm"
                        variant="danger"
                        aria-label={`Close the mapping of ${m.sourceId}`}
                        onClick={() => setClosing(m)}
                      >
                        Close
                      </Button>
                    ) : null,
                },
              ]}
            />
          )
        }
      </QueryBoundary>

      {draft !== null && (
        <MappingForm
          key={`${draft.source}/${draft.entityType}/${draft.sourceId}`}
          draft={draft}
          onClose={() => onDraftChange(null)}
          onCreated={() => onDraftChange(null)}
        />
      )}
      <ConfirmDialog
        open={closing !== null}
        danger
        title="Close this mapping?"
        description={
          closing && (
            <>
              {closing.sourceSystem} {closing.sourceEntityType} <b>{closing.sourceId}</b> stops
              mapping to {closing.canonicalId} from now. Events of an earlier time still map to it,
              and records that arrive for this id will be refused until it is mapped again.
            </>
          )
        }
        confirmLabel="Close mapping"
        onCancel={() => setClosing(null)}
        onConfirm={() => {
          const target = closing
          setClosing(null)
          if (!target) return
          close.mutate(
            { id: target.id },
            {
              onSuccess: () => toast(`Mapping of ${target.sourceId} closed`),
              onError: (error) => toast.error(problemText(error)),
            },
          )
        }}
      />
    </div>
  )
}
