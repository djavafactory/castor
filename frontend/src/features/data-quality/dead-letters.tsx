import { useState } from 'react'
import { toast } from 'sonner'
import { QueryBoundary } from '@/components/states/query-boundary'
import { EmptyState } from '@/components/states/states'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DataTable, type Column } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/ui/overlays'
import { useDeadLetters, useReplayDeadLetter, type DeadLetter } from '@/lib/api'
import { formatWhen, shortId } from '@/features/integrations/integrations-text'
import { problemText } from '@/features/integrations/problem-text'
import { REASON_HELP, STATE_LABEL, causeText } from './data-quality-text'

/**
 * The events the projector gave up on, newest first, with what became of each replay. A replay applies the stored event
 * again and is a new attempt linked to the dead letter: the dead letter itself is never changed or deleted.
 */
export function DeadLetters({ canReplay }: { canReplay: boolean }) {
  const query = useDeadLetters()
  const replay = useReplayDeadLetter()
  const [asking, setAsking] = useState<DeadLetter | null>(null)

  function confirm(letter: DeadLetter) {
    setAsking(null)
    replay.mutate(letter.id, {
      onSuccess: (result) => {
        if (result.outcome === 'APPLIED') toast(`Dead letter ${letter.id} replayed and applied`)
        else if (result.outcome === 'ALREADY_APPLIED')
          toast(`Dead letter ${letter.id}: the event had been applied already`)
        else toast.error(`Dead letter ${letter.id} failed again: ${causeText(result.attempt)}`)
      },
      onError: (error) => toast.error(problemText(error)),
    })
  }

  return (
    <section aria-labelledby="dead-letters-heading" className="flex flex-col gap-3">
      <h2 id="dead-letters-heading" className="text-sm font-bold">
        Dead letters
      </h2>
      {!canReplay && (
        <p className="text-xs text-muted" role="note">
          View only: an administrator replays dead letters.
        </p>
      )}
      <QueryBoundary query={query}>
        {(letters) =>
          letters.length === 0 ? (
            <EmptyState>
              No event was set aside. Every event the projector read was applied.
            </EmptyState>
          ) : (
            <DataTable
              caption="Dead letters, newest first"
              rows={letters}
              rowKey={(l) => String(l.id)}
              columns={columns(canReplay, replay.isPending, setAsking)}
            />
          )
        }
      </QueryBoundary>
      <ConfirmDialog
        open={asking !== null}
        title="Replay this dead letter?"
        description={
          asking
            ? `The stored ${asking.type ?? 'event'} is applied again. If it works, the state changes as that event says. The dead letter itself stays as it is and the replay is a new attempt linked to it.`
            : ''
        }
        confirmLabel="Replay"
        onConfirm={() => asking && confirm(asking)}
        onCancel={() => setAsking(null)}
      />
    </section>
  )
}

function columns(
  canReplay: boolean,
  busy: boolean,
  onReplay: (letter: DeadLetter) => void,
): Column<DeadLetter>[] {
  return [
    { key: 'at', header: 'Set aside', render: (l) => formatWhen(l.createdAt) },
    {
      key: 'reason',
      header: 'Reason',
      render: (l) => (
        <Badge variant="bad" title={REASON_HELP[l.reason]}>
          {l.reason}
        </Badge>
      ),
    },
    {
      key: 'event',
      header: 'Event',
      render: (l) => (
        <span className="inline-flex flex-col">
          <span className="font-mono">{l.type ?? 'unreadable'}</span>
          {l.entityId && (
            <span className="font-mono text-[11px] text-muted" title={l.entityId}>
              {shortId(l.entityId)}
            </span>
          )}
        </span>
      ),
    },
    { key: 'cause', header: 'Cause', render: (l) => causeText(l) },
    {
      key: 'state',
      header: 'Replay',
      render: (l) => (
        <Badge variant={l.state === 'REPLAYED' ? 'ok' : 'neutral'}>
          {STATE_LABEL[l.state]}
          {l.replays > 1 ? ` (${l.replays})` : ''}
        </Badge>
      ),
    },
    ...(canReplay
      ? [
          {
            key: 'action',
            header: 'Action',
            render: (l: DeadLetter) => (
              <Button
                aria-label={`Replay dead letter ${l.id}`}
                disabled={busy || l.state === 'REPLAYED'}
                title={
                  l.state === 'REPLAYED'
                    ? 'A replay of this dead letter was applied'
                    : 'Apply the stored event again'
                }
                onClick={() => onReplay(l)}
              >
                Replay
              </Button>
            ),
          },
        ]
      : []),
  ]
}
