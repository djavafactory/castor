import { useId, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { KeyValue, SectionHeading } from '@/components/ui/card'
import { useDraftValidation } from '@/features/design/issues/use-draft-validation'
import { ApiError } from '@/lib/api-client'
import { useUpdateAsset } from '@/lib/api'
import { notChanged } from '../use-inspector-commit'
import type { InspectorContext } from '../inspector-context'

const MIN_REASON = 10
const MAX_REASON = 500

/**
 * EXCLUSION OVERRIDE: shown only when the shape lies in an exclusion zone that does not let its type in, or when an
 * override is already recorded. The written reason is stored and audited with who and when; it is not undoable.
 */
export function OverrideSection({ ctx }: { ctx: InspectorContext }) {
  const { asset, readOnly } = ctx
  const { query } = useDraftValidation()
  const update = useUpdateAsset()
  const [reason, setReason] = useState('')
  const id = useId()
  const inZone =
    query.data?.issues.some(
      (i) => i.code === 'IN_EXCLUSION_ZONE' && i.assetIds.includes(asset.id),
    ) ?? false
  const recorded = asset.override
  if (!inZone && !recorded) return null

  const trimmed = reason.trim()
  const valid = trimmed.length >= MIN_REASON && trimmed.length <= MAX_REASON

  async function record() {
    if (!valid) return
    try {
      await update.mutateAsync({
        id: asset.id,
        rowVersion: asset.rowVersion,
        changes: { override: { reason: trimmed } },
      })
      setReason('')
    } catch (error) {
      if (error instanceof ApiError && (error.status === 400 || error.status === 422)) {
        toast(notChanged(error.errors[0]?.message ?? error.detail ?? error.title))
      }
    }
  }

  return (
    <section aria-label="Exclusion override">
      <SectionHeading>Exclusion override</SectionHeading>
      {recorded && (
        <KeyValue
          items={[
            ['Reason', recorded.reason],
            ['Recorded by', recorded.by ?? ''],
            ['When', recorded.at ? new Date(recorded.at).toLocaleString() : ''],
          ]}
        />
      )}
      {inZone && !recorded && (
        <div className="grid gap-2">
          <p className="m-0 text-xs text-muted">
            Inside a safety exclusion zone. Move the shape, or record a documented override with a
            written reason.
          </p>
          <label htmlFor={id} className="text-xs font-semibold">
            Reason
          </label>
          <textarea
            id={id}
            rows={3}
            maxLength={MAX_REASON}
            value={reason}
            disabled={readOnly}
            onChange={(event) => setReason(event.target.value)}
            className="w-full rounded-md border border-line bg-white px-2 py-[5px] text-[13px] text-ink disabled:opacity-40"
          />
          <p className="m-0 text-xs text-muted">
            {trimmed.length}/{MAX_REASON} characters, at least {MIN_REASON}
          </p>
          <div>
            <Button size="sm" disabled={readOnly || !valid} onClick={() => void record()}>
              Record override
            </Button>
          </div>
        </div>
      )}
    </section>
  )
}
