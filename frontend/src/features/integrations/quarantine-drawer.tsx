import { useState } from 'react'
import { toast } from 'sonner'
import { QueryBoundary } from '@/components/states/query-boundary'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DataTable, KeyValue, SectionHeading } from '@/components/ui/card'
import { Drawer } from '@/components/ui/drawer'
import { ConfirmDialog } from '@/components/ui/overlays'
import {
  useQuarantineDetail,
  useReplayRecord,
  type QuarantineDetail,
  type UnresolvedId,
} from '@/lib/api'
import {
  REASON_HELP,
  formatWhen,
  isMappingReason,
  publishedText,
  shortId,
} from './integrations-text'
import { JsonView } from './json-view'
import { problemText } from './problem-text'

/**
 * One quarantined record: why it was refused, the payload exactly as it was received (read-only, keys and secrets
 * masked), and every replay of it. Replaying creates a NEW record linked to this one; this one is never changed.
 */
export function QuarantineDrawer({
  receptionId,
  canAdminister,
  onOpen,
  onAddMapping,
  onClose,
}: {
  receptionId: string
  canAdminister: boolean
  /** Another record of the same chain: the original of a replay. */
  onOpen: (receptionId: string) => void
  onAddMapping: (id: Pick<UnresolvedId, 'source' | 'entityType' | 'sourceId'>) => void
  onClose: () => void
}) {
  const query = useQuarantineDetail(receptionId)
  return (
    <Drawer open title="Quarantined record" onClose={onClose}>
      <QueryBoundary query={query}>
        {(detail) => (
          <Body
            detail={detail}
            canAdminister={canAdminister}
            onOpen={onOpen}
            onAddMapping={(id) => {
              onClose()
              onAddMapping(id)
            }}
          />
        )}
      </QueryBoundary>
    </Drawer>
  )
}

function Body({
  detail,
  canAdminister,
  onOpen,
  onAddMapping,
}: {
  detail: QuarantineDetail
  canAdminister: boolean
  onOpen: (receptionId: string) => void
  onAddMapping: (id: Pick<UnresolvedId, 'source' | 'entityType' | 'sourceId'>) => void
}) {
  const { entry, attempts } = detail
  const replay = useReplayRecord()
  const [confirming, setConfirming] = useState(false)
  const waiting = attempts.some((a) => a.status === 'RECEIVED')
  const canReplay = canAdminister && detail.rawAvailable && !waiting

  return (
    <>
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant="bad">{entry.reasonCode}</Badge>
        <span className="text-xs text-muted">{REASON_HELP[entry.reasonCode]}</span>
      </div>
      <p className="mt-2 text-[13px] break-words">{entry.detail}</p>

      <KeyValue
        className="mt-3"
        items={[
          ['Reception id', entry.receptionId],
          ['Source', entry.source],
          ['Source event', entry.sourceEventId],
          ['Received', formatWhen(entry.receivedAt)],
          ['Quarantined', formatWhen(entry.quarantinedAt)],
          ...(entry.subjectId
            ? ([['Source id', `${entry.subjectType ?? ''} ${entry.subjectId}`.trim()]] as [
                string,
                string,
              ][])
            : []),
        ]}
      />

      {entry.replayOf && (
        <p className="mt-3 text-xs">
          This is a replay that was refused again.{' '}
          <button
            type="button"
            className="font-semibold underline"
            onClick={() => onOpen(entry.replayOf ?? '')}
          >
            Open the original record
          </button>
        </p>
      )}

      <div className="mt-3 flex flex-wrap gap-2">
        {canAdminister && (
          <Button
            variant="primary"
            disabled={!canReplay || replay.isPending}
            title={
              !detail.rawAvailable
                ? 'The payload was removed after its retention period'
                : waiting
                  ? 'An earlier replay has not been processed yet'
                  : 'Put this record through the pipeline again'
            }
            onClick={() => setConfirming(true)}
          >
            Replay
          </Button>
        )}
        {canAdminister && entry.subjectId && isMappingReason(entry.reasonCode) && (
          <Button
            onClick={() =>
              onAddMapping({
                source: entry.source,
                entityType: entry.subjectType ?? 'EQUIPMENT',
                sourceId: entry.subjectId ?? '',
              })
            }
          >
            Add mapping for {entry.subjectId}
          </Button>
        )}
      </div>
      {!detail.rawAvailable && (
        <p role="note" className="mt-2 text-xs text-warn-soft-fg">
          The payload was removed after its retention period, so this record cannot be replayed.
        </p>
      )}

      <SectionHeading>Replays of this record</SectionHeading>
      {attempts.length === 0 ? (
        <p className="text-xs text-muted">Not replayed.</p>
      ) : (
        <DataTable
          caption="Replays of the original record, oldest first"
          rows={attempts}
          rowKey={(a) => a.receptionId}
          columns={[
            {
              key: 'id',
              header: 'Record',
              render: (a) =>
                a.receptionId === entry.receptionId ? (
                  <span className="font-mono" title={a.receptionId}>
                    {shortId(a.receptionId)} (this one)
                  </span>
                ) : (
                  <button
                    type="button"
                    className="font-mono underline"
                    title={a.receptionId}
                    onClick={() => onOpen(a.receptionId)}
                  >
                    {shortId(a.receptionId)}
                  </button>
                ),
            },
            { key: 'at', header: 'Received', render: (a) => formatWhen(a.receivedAt) },
            {
              key: 'status',
              header: 'Result',
              render: (a) =>
                a.status === 'NORMALIZED' ? (
                  <Badge variant="ok">Accepted</Badge>
                ) : a.status === 'RECEIVED' ? (
                  <Badge variant="neutral">Waiting</Badge>
                ) : (
                  <Badge variant="bad">{a.reasonCode ?? 'Refused'}</Badge>
                ),
            },
            {
              key: 'published',
              header: 'Published',
              render: (a) =>
                a.status === 'NORMALIZED' ? publishedText(a.events, a.published) : '',
            },
          ]}
        />
      )}

      <SectionHeading>Payload as received</SectionHeading>
      {detail.rawAvailable ? (
        <JsonView value={detail.raw} label="Payload (read-only)" />
      ) : (
        <p className="text-xs text-muted">Removed.</p>
      )}

      <ConfirmDialog
        open={confirming}
        title="Replay this record?"
        description="A new record with the same payload goes through the pipeline again. This record and its quarantine entry stay as they are. If it is still refused, you will see a second attempt linked to it."
        confirmLabel="Replay"
        onCancel={() => setConfirming(false)}
        onConfirm={() => {
          setConfirming(false)
          replay.mutate(entry.receptionId, {
            onSuccess: () => toast('Replay queued'),
            onError: (error) => toast.error(problemText(error)),
          })
        }}
      />
    </>
  )
}
