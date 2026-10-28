import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { QueryBoundary } from '@/components/states/query-boundary'
import { EmptyState } from '@/components/states/states'
import { Tag } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DataTable, SectionHeading } from '@/components/ui/card'
import { Input } from '@/components/ui/field'
import { ConfirmDialog } from '@/components/ui/overlays'
import { LayoutStatusBadge } from '@/features/design/layout-status-badge'
import { draftSignature } from '@/features/design/issues/use-draft-validation'
import { formatPublishedAt } from '@/features/shell/published-label'
import { ApiError } from '@/lib/api-client'
import {
  fetchValidation,
  useDiscardDraft,
  useDraftLayout,
  useLayoutDiff,
  useLayoutVersions,
  usePort,
  usePublishLayout,
  useRestoreVersion,
  useReviewers,
  useStartBlankLayout,
  type LayoutDiff,
  type LayoutVersion,
} from '@/lib/api'
import { canPublish } from '@/lib/roles'
import { useAuthStore } from '@/lib/stores/auth-store'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { startFreshDraft } from './fresh-draft'
import { downloadExport } from './download-export'
import { GeoJsonExchange } from './geojson-exchange'
import { PublishDialog } from './publish-dialog'
import {
  BLANK_MESSAGE,
  BLANK_PROMPT,
  DISCARD_PROMPT,
  NOT_A_PUBLISHER_TIP,
  NOTE_PLACEHOLDER,
  PUBLISH_TIP,
  assetsText,
  draftStatusText,
  diffSummary,
  formatChangeTime,
  publishBlockedMessage,
  publishedMessage,
  restorePrompt,
  restoredMessage,
} from './versions-text'

const NOTE_MAX = 500

type Confirming =
  { kind: 'discard' } | { kind: 'blank' } | { kind: 'restore'; version: LayoutVersion }

function problemTitle(error: unknown): string {
  return error instanceof ApiError ? error.title : 'Something went wrong. Try again.'
}

/**
 * Right panel tab "Versions" (DESIGNER-09): the state of the draft, what it changes against the published version, the
 * publish note and the buttons, then every published version with Restore, then "New blank layout".
 */
export function VersionsTab() {
  const client = useQueryClient()
  const roles = useAuthStore((s) => s.roles)
  const publisher = canPublish(roles)
  const setRightTab = useUiStore((s) => s.setRightTab)
  const timeZone = usePort().data?.timeZone

  const draftQuery = useDraftLayout(undefined, true, { sync: true })
  const draft = draftQuery.data
  const versions = useLayoutVersions()
  const published = versions.data?.find((v) => v.status === 'PUBLISHED')
  const diff = useLayoutDiff(
    draft?.versionId ?? null,
    published?.id ?? null,
    draft ? draftSignature(draft) : '',
  )
  const reviewers = useReviewers(publisher)

  const saveChecking = useEditorStore((s) => s.checking)
  const checkMessage = useEditorStore((s) => s.checkMessage)
  const publish = usePublishLayout()
  const restore = useRestoreVersion()
  const discard = useDiscardDraft()
  const blank = useStartBlankLayout()

  const [note, setNote] = useState('')
  const [checking, setChecking] = useState(false)
  const [publishing, setPublishing] = useState(false)
  const [confirming, setConfirming] = useState<Confirming | null>(null)

  async function onPublish() {
    if (!draft) return
    setChecking(true)
    try {
      const report = await fetchValidation(draft.versionId)
      if (report.errors > 0) {
        toast.error(publishBlockedMessage(report.errors))
        setRightTab('issues')
        void client.invalidateQueries({ queryKey: ['validation'] })
        return
      }
      setPublishing(true)
    } catch (error) {
      toast.error(problemTitle(error))
    } finally {
      setChecking(false)
    }
  }

  function confirmPublish(reviewedBy: string | null) {
    if (!draft) return
    setPublishing(false)
    publish.mutate(
      { layoutId: draft.versionId, note: note.trim(), reviewedBy },
      {
        onSuccess: (result) => {
          toast(publishedMessage(result.versionNo))
          setNote('')
          void startFreshDraft(client)
        },
        onError: (error) => {
          toast.error(problemTitle(error))
          if (error instanceof ApiError && error.status === 422) setRightTab('issues')
        },
      },
    )
  }

  function onConfirm(action: Confirming) {
    setConfirming(null)
    if (action.kind === 'discard' && draft) {
      discard.mutate(draft.versionId, {
        onSuccess: () => {
          toast('Draft discarded. A new draft was started from the published layout.')
          void startFreshDraft(client)
        },
        onError: (error) => toast.error(problemTitle(error)),
      })
    } else if (action.kind === 'blank') {
      blank.mutate(draft?.versionId ?? null, {
        onSuccess: () => {
          useUiStore.getState().setBasemap('sentinel2')
          toast(BLANK_MESSAGE)
        },
        onError: (error) => toast.error(problemTitle(error)),
      })
    } else if (action.kind === 'restore') {
      restore.mutate(
        { versionId: action.version.id, discardDraft: draft !== undefined },
        {
          onSuccess: () => toast(restoredMessage(action.version.versionNo)),
          onError: (error) => toast.error(problemTitle(error)),
        },
      )
    }
  }

  const busy =
    checking || publish.isPending || restore.isPending || discard.isPending || blank.isPending

  return (
    <section aria-label="Versions">
      <SectionHeading className="mt-0">This layout</SectionHeading>
      <dl className="grid grid-cols-[70px_1fr] items-baseline gap-x-2 gap-y-1.5 text-[13px]">
        <dt className="text-xs text-muted">Status</dt>
        <dd className="m-0">
          <LayoutStatusBadge />
        </dd>
        <dt className="text-xs text-muted">Published</dt>
        <dd className="m-0">{published ? `version ${published.versionNo}` : 'none yet'}</dd>
        <dt className="text-xs text-muted">Draft</dt>
        <dd className="m-0">
          {draft ? (
            <>
              {draft.basedOnVersionNo == null
                ? draftStatusText(null).toLowerCase()
                : `based on v${draft.basedOnVersionNo}`}
              {draft.updatedAt &&
                `, last change ${formatChangeTime(draft.updatedAt, timeZone)} by ${draft.updatedBy ?? 'unknown'}`}
            </>
          ) : (
            'none'
          )}
        </dd>
      </dl>

      <WhatChanged
        hasDraft={draft !== undefined}
        hasPublished={published !== undefined}
        query={diff}
      />

      <div className="mt-3 flex flex-col gap-1">
        <label htmlFor="publish-note" className="text-xs font-semibold">
          Publish note
        </label>
        <Input
          id="publish-note"
          value={note}
          maxLength={NOTE_MAX}
          placeholder={NOTE_PLACEHOLDER}
          onChange={(e) => setNote(e.target.value)}
        />
      </div>

      <div className="mt-3 flex flex-wrap gap-2">
        <Button
          disabled={!draft || busy || saveChecking}
          title="Wait for pending saves and re-validate (Ctrl+S)"
          onClick={() => useEditorStore.getState().requestSave()}
        >
          {saveChecking ? 'Checking...' : 'Check and save'}
        </Button>
        <span title={publisher ? PUBLISH_TIP : NOT_A_PUBLISHER_TIP}>
          <Button
            variant="primary"
            disabled={!publisher || !draft || busy}
            onClick={() => void onPublish()}
          >
            {checking ? 'Checking...' : 'Publish'}
          </Button>
        </span>
        {publisher && (
          <Button
            variant="danger"
            disabled={!draft || busy}
            onClick={() => setConfirming({ kind: 'discard' })}
          >
            Discard draft
          </Button>
        )}
      </div>

      {checkMessage && <p className="mt-2 text-xs text-ink">{checkMessage}</p>}

      <SectionHeading>Published versions</SectionHeading>
      <QueryBoundary query={versions}>
        {(list) => {
          const history = list.filter((v) => v.status === 'PUBLISHED' || v.status === 'SUPERSEDED')
          if (history.length === 0) return <EmptyState>Nothing is published yet.</EmptyState>
          return (
            <DataTable
              caption="Published versions"
              rows={history}
              rowKey={(v) => v.id}
              columns={[
                {
                  key: 'version',
                  header: 'Version',
                  render: (v) => (
                    <span className="flex flex-col gap-0.5">
                      <span className="flex items-center gap-1.5">
                        <strong>{`v${v.versionNo}`}</strong>
                        {v.status === 'PUBLISHED' && <Tag>live</Tag>}
                      </span>
                      {v.restoredFromVersionNo != null && (
                        <span className="text-muted">{`restored from v${v.restoredFromVersionNo}`}</span>
                      )}
                    </span>
                  ),
                },
                {
                  key: 'when',
                  header: 'When / who',
                  render: (v) => (
                    <span className="flex flex-col gap-0.5">
                      <span>{`${v.publishedAt ? formatPublishedAt(v.publishedAt, timeZone) : '-'}, ${v.publishedBy ?? '-'}, ${assetsText(v.assetCount)}`}</span>
                      {v.reviewedBy && (
                        <span className="text-muted">{`reviewed by ${v.reviewedBy}`}</span>
                      )}
                    </span>
                  ),
                },
                { key: 'note', header: 'Note', render: (v) => v.note || '-' },
                {
                  key: 'actions',
                  header: '',
                  render: (v) => (
                    <span className="flex gap-1">
                      <Button
                        size="sm"
                        aria-label={`Export version ${v.versionNo} as GeoJSON`}
                        disabled={busy}
                        onClick={() => void downloadExport(v.id)}
                      >
                        Export
                      </Button>
                      {publisher && (
                        <Button
                          size="sm"
                          aria-label={`Restore version ${v.versionNo}`}
                          disabled={busy}
                          onClick={() => setConfirming({ kind: 'restore', version: v })}
                        >
                          Restore
                        </Button>
                      )}
                    </span>
                  ),
                },
              ]}
            />
          )
        }}
      </QueryBoundary>

      <GeoJsonExchange draftId={draft?.versionId} disabled={busy} />

      {publisher && (
        <>
          <SectionHeading>Start over</SectionHeading>
          <Button disabled={busy} onClick={() => setConfirming({ kind: 'blank' })}>
            New blank layout
          </Button>
        </>
      )}

      <PublishDialog
        open={publishing}
        summary={diff.data ? diffSummary(diff.data) : 'no comparison available'}
        note={note.trim()}
        reviewers={reviewers.data ?? []}
        onConfirm={confirmPublish}
        onCancel={() => setPublishing(false)}
      />
      <ConfirmDialog
        open={confirming?.kind === 'discard'}
        title="Discard draft"
        description={DISCARD_PROMPT}
        confirmLabel="Discard draft"
        danger
        onConfirm={() => confirming && onConfirm(confirming)}
        onCancel={() => setConfirming(null)}
      />
      <ConfirmDialog
        open={confirming?.kind === 'blank'}
        title="New blank layout"
        description={BLANK_PROMPT}
        confirmLabel="Start blank"
        onConfirm={() => confirming && onConfirm(confirming)}
        onCancel={() => setConfirming(null)}
      />
      <ConfirmDialog
        open={confirming?.kind === 'restore'}
        title="Restore a version"
        description={
          confirming?.kind === 'restore' ? (
            <>
              <span className="block">{restorePrompt(confirming.version.versionNo)}</span>
              {draft && <span className="mt-1 block">The current draft is discarded.</span>}
            </>
          ) : (
            ''
          )
        }
        confirmLabel="Restore"
        onConfirm={() => confirming && onConfirm(confirming)}
        onCancel={() => setConfirming(null)}
      />
    </section>
  )
}

/** "What changed": the counts against the published version, with the list behind them. */
function WhatChanged({
  hasDraft,
  hasPublished,
  query,
}: {
  hasDraft: boolean
  hasPublished: boolean
  query: { data?: LayoutDiff; error: unknown; isPending: boolean }
}) {
  if (!hasDraft || !hasPublished) return null
  return (
    <div className="mt-3">
      <h4 className="text-xs font-semibold">What changed</h4>
      {query.error ? (
        <p className="mt-1 text-xs text-muted">The changes could not be read.</p>
      ) : query.data === undefined ? (
        <p className="mt-1 text-xs text-muted">Comparing...</p>
      ) : (
        <details className="mt-1 text-[13px]">
          <summary className="cursor-pointer">{diffSummary(query.data)}</summary>
          <ul className="mt-1.5 flex flex-col gap-0.5 text-xs">
            {query.data.added.map((a) => (
              <li key={`a${a.assetId}`}>{`+ ${a.code} ${a.name}`}</li>
            ))}
            {query.data.changed.map((c) => (
              <li key={`c${c.assetId}`}>{`~ ${c.code} ${c.name} (${c.fields.join(', ')})`}</li>
            ))}
            {query.data.removed.map((r) => (
              <li key={`r${r.assetId}`}>{`- ${r.code} ${r.name}`}</li>
            ))}
          </ul>
        </details>
      )}
    </div>
  )
}
