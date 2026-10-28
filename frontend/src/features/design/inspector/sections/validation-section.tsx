import { StatusDot } from '@/components/ui/badge'
import { SectionHeading } from '@/components/ui/card'
import { useDraftValidation } from '@/features/design/issues/use-draft-validation'
import type { InspectorContext } from '../inspector-context'

/** VALIDATION: the rules that name this asset, from the same check the Issues tab shows. */
export function ValidationSection({ ctx }: { ctx: InspectorContext }) {
  const { query } = useDraftValidation()
  const issues = query.data?.issues.filter((i) => i.assetIds.includes(ctx.asset.id)) ?? []
  return (
    <section aria-label="Validation">
      <SectionHeading>Validation</SectionHeading>
      {!query.data ? (
        <p className="m-0 text-[13px] text-muted">Checking...</p>
      ) : issues.length === 0 ? (
        <p className="m-0 text-[13px] text-muted">No issues.</p>
      ) : (
        <ul className="m-0 grid list-none gap-1.5 p-0">
          {issues.map((issue, i) => (
            <li key={`${issue.code}:${i}`} className="flex items-start gap-2 text-[13px] text-ink">
              <StatusDot
                status={issue.level === 'ERROR' ? 'bad' : 'warn'}
                label={issue.level === 'ERROR' ? 'Error' : 'Warning'}
                className="mt-1.5"
              />
              <span className="min-w-0">{issue.message}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
