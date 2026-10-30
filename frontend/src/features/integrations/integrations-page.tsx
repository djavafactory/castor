import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { Logo } from '@/components/logo'
import { Tabs } from '@/components/ui/tabs'
import { usePort } from '@/lib/api'
import { canAdministerIntegrations } from '@/lib/roles'
import { useAuthStore } from '@/lib/stores/auth-store'
import type { MappingDraft } from './mapping-form'
import { MappingsTab } from './mappings-tab'
import { QuarantineTab, type QuarantineFilterState } from './quarantine-tab'
import { SourcesTab } from './sources-tab'

const TABS = [
  { value: 'sources', label: 'Sources' },
  { value: 'quarantine', label: 'Quarantine' },
  { value: 'mappings', label: 'Mappings' },
] as const

type TabId = (typeof TABS)[number]['value']

function tabOf(value: string | null): TabId {
  return TABS.find((t) => t.value === value)?.value ?? 'sources'
}

/**
 * Integrations: one page to find out why ingestion is not working. Sources says who sent what and when, Quarantine what
 * was refused and why, Mappings which source ids are known. OPERATOR may look; ADMIN may replay records, manage API
 * keys and change mappings. Reconciliation items and core dead letters are in the Data quality panel, not here.
 */
export function IntegrationsPage() {
  const roles = useAuthStore((s) => s.roles)
  const username = useAuthStore((s) => s.username)
  const port = usePort()
  const [params, setParams] = useSearchParams()
  const tab = tabOf(params.get('tab'))
  const canAdminister = canAdministerIntegrations(roles)
  const [quarantineFilter, setQuarantineFilter] = useState<QuarantineFilterState>({
    source: '',
    reason: '',
  })
  const [draft, setDraft] = useState<MappingDraft | null>(null)

  function show(next: TabId) {
    setParams(next === 'sources' ? {} : { tab: next }, { replace: true })
  }

  function showQuarantine(source: string) {
    setQuarantineFilter({ source, reason: '' })
    show('quarantine')
  }

  function addMapping(id: MappingDraft) {
    setDraft(id)
    show('mappings')
  }

  return (
    <div className="grid h-full grid-rows-[56px_minmax(0,1fr)] overflow-hidden bg-paper">
      <header className="flex items-center gap-3 border-b border-line-soft bg-paper px-4">
        <div className="flex items-center gap-2.5">
          <Logo />
          <span className="font-brand text-lg font-bold">Castor</span>
        </div>
        <span aria-hidden="true" className="h-[18px] w-px bg-line" />
        <span className="text-sm font-medium">{port.data?.name ?? 'Port'}</span>
        <nav aria-label="Pages" className="ml-2 flex gap-1">
          <Link to="/" className="rounded-full px-3 py-1 text-[13px] font-medium hover:bg-paper-2">
            Monitor
          </Link>
          <Link
            to="/integrations"
            aria-current="page"
            className="rounded-full bg-ink px-3 py-1 text-[13px] font-medium text-paper"
          >
            Integrations
          </Link>
        </nav>
        <span className="ml-auto text-sm font-medium">{username}</span>
      </header>
      <main className="flex min-h-0 flex-col" aria-label="Integrations">
        <div className="flex items-center gap-3 border-b border-line-soft pr-4">
          <Tabs
            panel
            label="Integrations"
            value={tab}
            tabs={[...TABS]}
            onChange={show}
            className="min-w-0 flex-1 border-b-0"
          />
          {!canAdminister && (
            <span className="text-xs text-muted" role="note">
              View only: an administrator replays records and changes keys and mappings.
            </span>
          )}
        </div>
        <div
          role="tabpanel"
          id={`tabpanel-${tab}`}
          aria-labelledby={`tab-${tab}`}
          tabIndex={0}
          className="min-h-0 flex-1 overflow-auto p-4"
        >
          {tab === 'sources' && (
            <SourcesTab canAdminister={canAdminister} onShowQuarantine={showQuarantine} />
          )}
          {tab === 'quarantine' && (
            <QuarantineTab
              filter={quarantineFilter}
              onFilterChange={setQuarantineFilter}
              canAdminister={canAdminister}
              onAddMapping={addMapping}
            />
          )}
          {tab === 'mappings' && (
            <MappingsTab
              canAdminister={canAdminister}
              draft={draft}
              onDraftChange={setDraft}
              onShowQuarantine={showQuarantine}
            />
          )}
        </div>
      </main>
    </div>
  )
}
