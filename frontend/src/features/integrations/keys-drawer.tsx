import { useState } from 'react'
import { toast } from 'sonner'
import { QueryBoundary } from '@/components/states/query-boundary'
import { EmptyState } from '@/components/states/states'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DataTable } from '@/components/ui/card'
import { Drawer } from '@/components/ui/drawer'
import { ConfirmDialog } from '@/components/ui/overlays'
import {
  useCreateIntegrationKey,
  useIntegrationKeys,
  useRevokeIntegrationKey,
  useRotateIntegrationKey,
  type IntegrationKey,
} from '@/lib/api'
import { formatWhen } from './integrations-text'
import { problemText } from './problem-text'

type Confirming = { kind: 'rotate' | 'revoke'; key: IntegrationKey }

/**
 * The API keys of one source. The page mounts it with the source as its key, so the secret of a new key is gone as soon
 * as the drawer is closed.
 * A new key is shown once, here, right after it is created or rotated; the server keeps only
 * its hash, so it cannot be shown again. Only an administrator can create, rotate or revoke.
 */
export function KeysDrawer({
  source,
  canAdminister,
  onClose,
}: {
  source: string
  canAdminister: boolean
  onClose: () => void
}) {
  const keys = useIntegrationKeys(source)
  const create = useCreateIntegrationKey()
  const rotate = useRotateIntegrationKey()
  const revoke = useRevokeIntegrationKey()
  const [fresh, setFresh] = useState<IntegrationKey | null>(null)
  const [confirming, setConfirming] = useState<Confirming | null>(null)

  function show(key: IntegrationKey, message: string) {
    setFresh(key)
    toast(message)
  }

  return (
    <Drawer open title={`API keys of ${source}`} onClose={onClose}>
      {canAdminister && (
        <Button
          variant="primary"
          disabled={create.isPending}
          onClick={() =>
            create.mutate(source, {
              onSuccess: (key) => show(key, 'Key created. Copy it now: it is shown once.'),
              onError: (error) => toast.error(problemText(error)),
            })
          }
        >
          Create key
        </Button>
      )}
      {fresh?.key && (
        <section
          aria-label="New key"
          className="mt-3 rounded-xl border border-warn bg-warn-soft p-3 text-warn-soft-fg"
        >
          <p className="text-xs font-semibold">Copy this key now. It is shown once.</p>
          <code
            data-testid="new-key"
            className="mt-1.5 block rounded-md bg-paper p-2 font-mono text-xs break-all text-ink"
          >
            {fresh.key}
          </code>
          <div className="mt-2 flex gap-2">
            <Button
              size="sm"
              onClick={() => {
                void navigator.clipboard
                  ?.writeText(fresh.key ?? '')
                  .then(() => toast('Key copied'))
                  .catch(() => toast.error('Could not copy: select the key and copy it by hand'))
              }}
            >
              Copy
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setFresh(null)}>
              I have saved it
            </Button>
          </div>
        </section>
      )}
      <div className="mt-3">
        <QueryBoundary query={keys}>
          {({ items }) =>
            items.length === 0 ? (
              <EmptyState>This source has no key yet.</EmptyState>
            ) : (
              <DataTable
                caption={`API keys of ${source}`}
                rows={items}
                rowKey={(k) => String(k.id)}
                columns={[
                  {
                    key: 'prefix',
                    header: 'Key',
                    render: (k) => <span className="font-mono">{k.prefix}...</span>,
                  },
                  {
                    key: 'origin',
                    header: 'Origin',
                    render: (k) =>
                      k.origin === 'env' ? (
                        <Badge variant="neutral" title="Set by GATEWAY_DEMO_KEY: change it there">
                          Environment
                        </Badge>
                      ) : (
                        'Issued here'
                      ),
                  },
                  { key: 'created', header: 'Created', render: (k) => formatWhen(k.createdAt) },
                  {
                    key: 'state',
                    header: 'State',
                    render: (k) =>
                      k.revokedAt ? (
                        <Badge variant="bad" title={formatWhen(k.revokedAt)}>
                          Revoked
                        </Badge>
                      ) : (
                        <Badge variant="ok">Active</Badge>
                      ),
                  },
                  {
                    key: 'actions',
                    header: 'Actions',
                    render: (k) =>
                      canAdminister && !k.revokedAt && k.origin === 'issued' ? (
                        <span className="inline-flex gap-1.5">
                          <Button
                            size="sm"
                            aria-label={`Rotate key ${k.prefix}`}
                            onClick={() => setConfirming({ kind: 'rotate', key: k })}
                          >
                            Rotate
                          </Button>
                          <Button
                            size="sm"
                            variant="danger"
                            aria-label={`Revoke key ${k.prefix}`}
                            onClick={() => setConfirming({ kind: 'revoke', key: k })}
                          >
                            Revoke
                          </Button>
                        </span>
                      ) : null,
                  },
                ]}
              />
            )
          }
        </QueryBoundary>
      </div>
      <ConfirmDialog
        open={confirming !== null}
        danger
        title={confirming?.kind === 'rotate' ? 'Rotate this key?' : 'Revoke this key?'}
        description={
          confirming?.kind === 'rotate'
            ? `The key ${confirming.key.prefix}... stops working at once and a new one is issued. ${source} must be given the new key.`
            : `The key ${confirming?.key.prefix}... stops working at once. A source that still uses it is refused (401).`
        }
        confirmLabel={confirming?.kind === 'rotate' ? 'Rotate key' : 'Revoke key'}
        onCancel={() => setConfirming(null)}
        onConfirm={() => {
          const action = confirming
          setConfirming(null)
          if (!action) return
          const input = { source, keyId: action.key.id }
          if (action.kind === 'rotate') {
            rotate.mutate(input, {
              onSuccess: (key) => show(key, 'Key rotated. Copy the new key now.'),
              onError: (error) => toast.error(problemText(error)),
            })
          } else {
            revoke.mutate(input, {
              onSuccess: () => toast('Key revoked'),
              onError: (error) => toast.error(problemText(error)),
            })
          }
        }}
      />
    </Drawer>
  )
}
