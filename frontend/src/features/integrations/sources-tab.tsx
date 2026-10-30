import { useState } from 'react'
import { QueryBoundary } from '@/components/states/query-boundary'
import { EmptyState } from '@/components/states/states'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DataTable, type Column } from '@/components/ui/card'
import { Input, Label, Select } from '@/components/ui/field'
import { useIntegrationSources, type Freshness, type IntegrationSource } from '@/lib/api'
import { KeysDrawer } from './keys-drawer'
import { FRESHNESS_LABEL, formatWhen, slaText } from './integrations-text'

const FRESHNESS_BADGE = { FRESH: 'ok', STALE: 'warn', UNKNOWN: 'neutral' } as const

type FreshnessFilter = Freshness | 'ALL'

/**
 * What each source sent in the last 24 hours: accepted, repeated and refused, when it last sent anything and whether
 * that is recent enough. A source's keys are managed from here.
 */
export function SourcesTab({
  canAdminister,
  onShowQuarantine,
}: {
  canAdminister: boolean
  onShowQuarantine: (source: string) => void
}) {
  const query = useIntegrationSources()
  const [freshness, setFreshness] = useState<FreshnessFilter>('ALL')
  const [text, setText] = useState('')
  const [keysOf, setKeysOf] = useState<string | null>(null)

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1">
          <Label htmlFor="sources-freshness">Freshness</Label>
          <Select
            id="sources-freshness"
            value={freshness}
            onChange={(e) => setFreshness(e.target.value as FreshnessFilter)}
          >
            <option value="ALL">All</option>
            <option value="FRESH">Fresh</option>
            <option value="STALE">Stale</option>
            <option value="UNKNOWN">Never seen</option>
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="sources-text">Source</Label>
          <Input
            id="sources-text"
            value={text}
            placeholder="Filter by name"
            onChange={(e) => setText(e.target.value)}
          />
        </div>
      </div>
      <QueryBoundary query={query}>
        {({ items }) => {
          const rows = items.filter(
            (s) =>
              (freshness === 'ALL' || s.freshness === freshness) &&
              s.code.includes(text.trim().toLowerCase()),
          )
          if (items.length === 0) {
            return <EmptyState>No source is configured for this port.</EmptyState>
          }
          if (rows.length === 0) {
            return <EmptyState>No source matches the filter.</EmptyState>
          }
          return (
            <DataTable
              caption="Sources and their counters of the last 24 hours"
              rows={rows}
              rowKey={(s) => s.code}
              columns={columns(onShowQuarantine, setKeysOf)}
            />
          )
        }}
      </QueryBoundary>
      <p className="text-xs text-muted">
        Counters cover the last 24 hours. A source is stale when its last record is older than its
        freshness limit.
      </p>
      {keysOf !== null && (
        <KeysDrawer
          key={keysOf}
          source={keysOf}
          canAdminister={canAdminister}
          onClose={() => setKeysOf(null)}
        />
      )}
    </div>
  )
}

function columns(
  onShowQuarantine: (source: string) => void,
  onKeys: (source: string) => void,
): Column<IntegrationSource>[] {
  return [
    {
      key: 'code',
      header: 'Source',
      render: (s) => (
        <span className="inline-flex items-center gap-1.5">
          <span className="font-mono font-semibold">{s.code}</span>
          {!s.enabled && <Badge variant="neutral">Off</Badge>}
        </span>
      ),
    },
    {
      key: 'freshness',
      header: 'Freshness',
      render: (s) => (
        <Badge
          variant={FRESHNESS_BADGE[s.freshness]}
          title={`Limit ${slaText(s.freshnessSlaSeconds)}`}
        >
          {FRESHNESS_LABEL[s.freshness]}
        </Badge>
      ),
    },
    { key: 'last', header: 'Last received', render: (s) => formatWhen(s.lastReceivedAt) },
    { key: 'accepted', header: 'Accepted', numeric: true, render: (s) => s.accepted },
    { key: 'duplicate', header: 'Duplicate', numeric: true, render: (s) => s.duplicate },
    {
      key: 'quarantined',
      header: 'Quarantined',
      numeric: true,
      render: (s) =>
        s.quarantined === 0 ? (
          0
        ) : (
          <button
            type="button"
            className="font-mono font-semibold text-bad underline"
            aria-label={`${s.quarantined} quarantined from ${s.code}: show them`}
            onClick={() => onShowQuarantine(s.code)}
          >
            {s.quarantined}
          </button>
        ),
    },
    { key: 'keys', header: 'Keys', numeric: true, render: (s) => s.activeKeys },
    {
      key: 'manage',
      header: 'Actions',
      render: (s) => (
        <Button size="sm" aria-label={`Keys of ${s.code}`} onClick={() => onKeys(s.code)}>
          Keys
        </Button>
      ),
    },
  ]
}
