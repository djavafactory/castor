import { RefreshCw } from 'lucide-react'
import { QueryBoundary } from '@/components/states/query-boundary'
import { EmptyState } from '@/components/states/states'
import { Button } from '@/components/ui/button'
import { StatusDot } from '@/components/ui/badge'
import { SectionHeading } from '@/components/ui/card'
import { goToAsset } from '@/features/search/go-to-place'
import type { Layout, ValidationIssue } from '@/lib/api'
import { cn } from '@/lib/utils'
import { issueNames, issueSummary } from './issue-text'
import { useDraftValidation } from './use-draft-validation'

/** The first asset of the issue as the draft knows it: its name and its shape. */
function assetOf(layout: Layout, id: string) {
  return layout.assets.find((a) => a.id === id)
}

function nameOf(layout: Layout, id: string): string | undefined {
  const name = assetOf(layout, id)?.name
  return typeof name === 'string' && name !== '' ? name : undefined
}

function IssueRow({ issue, layout }: { issue: ValidationIssue; layout: Layout }) {
  const first = issue.assetIds[0] === undefined ? undefined : assetOf(layout, issue.assetIds[0])
  const names = issueNames(issue, (id) => nameOf(layout, id))
  // A row about one shape that is already named in the message needs no second line
  const firstName = first ? nameOf(layout, first.id) : undefined
  const redundant =
    issue.assetIds.length === 1 && firstName !== undefined && issue.message.includes(firstName)
  const body = (
    <>
      <StatusDot
        status={issue.level === 'ERROR' ? 'bad' : 'warn'}
        label={issue.level === 'ERROR' ? 'Error' : 'Warning'}
        className="mt-[5px]"
      />
      <span className="min-w-0 flex-1">
        <span className="block text-[13px] text-ink">{issue.message}</span>
        {names !== '' && !redundant && (
          <span className="mt-0.5 block truncate text-xs text-muted">{names}</span>
        )}
      </span>
    </>
  )
  const className = 'flex w-full items-start gap-2 rounded-md px-2 py-1.5 text-left'
  if (!first) return <div className={className}>{body}</div>
  return (
    <button
      type="button"
      className={`${className} hover:bg-paper-2 focus-visible:bg-paper-2`}
      onClick={() => goToAsset(first.id, first.geometry)}
    >
      {body}
    </button>
  )
}

/**
 * Right panel tab "Issues" (DESIGNER-03): everything that blocks publishing and everything to look at, from the same
 * rule catalogue the server applies to each save. A row selects its first asset and zooms to it.
 */
export function IssuesTab() {
  const { draft, query } = useDraftValidation()
  return (
    <section aria-label="Issues">
      <div className="flex items-center justify-between gap-2">
        <SectionHeading className="mt-0 mb-0">Geometry and placement</SectionHeading>
        {/* The name stays "Check again" while it runs (a spinner says so); a press then starts the check over */}
        <Button
          size="sm"
          aria-busy={query.isFetching}
          title={query.isFetching ? 'Checking the draft...' : 'Check the draft again'}
          onClick={() => void query.refetch()}
        >
          <RefreshCw
            aria-hidden="true"
            className={cn(
              'size-3.5',
              query.isFetching && 'animate-spin motion-reduce:animate-none',
            )}
          />
          Check again
        </Button>
      </div>
      <QueryBoundary query={draft}>
        {(layout) => (
          <QueryBoundary query={query}>
            {(report) => (
              <div className="mt-2">
                <p className="text-xs font-semibold text-ink">
                  {issueSummary(report.errors, report.warnings)}
                </p>
                {report.issues.length === 0 ? (
                  <EmptyState>No issues. The layout can be published.</EmptyState>
                ) : (
                  <ul aria-label="Issues found" className="-mx-2 mt-1.5">
                    {report.issues.map((issue, i) => (
                      <li key={`${i}:${issue.code}:${issue.assetIds.slice(0, 2).join(',')}`}>
                        <IssueRow issue={issue} layout={layout} />
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}
          </QueryBoundary>
        )}
      </QueryBoundary>
    </section>
  )
}
