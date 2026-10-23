import { Layers, Ruler } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import { Logo } from '@/components/logo'
import {
  EmptyState,
  ErrorCard,
  LoadingCard,
  SaveStatus,
  SkeletonRows,
  TilesNote,
} from '@/components/states/states'
import { Badge, CountPill, LockedBadge, NoSurveyBadge, StatusDot, Tag } from '@/components/ui/badge'
import { Button, IconButton } from '@/components/ui/button'
import {
  Card,
  DataTable,
  FloatingCard,
  KeyValue,
  ProgressBar,
  SectionHeading,
} from '@/components/ui/card'
import { Input, Label, Select } from '@/components/ui/field'
import {
  ConfirmDialog,
  Menu,
  MenuContent,
  MenuItem,
  MenuTrigger,
  Popover,
  PopoverContent,
  PopoverTrigger,
  Tooltip,
} from '@/components/ui/overlays'
import { Segmented } from '@/components/ui/segmented'
import { Tabs } from '@/components/ui/tabs'

const TOKENS = [
  'canvas',
  'paper',
  'paper-2',
  'line',
  'line-soft',
  'line-strong',
  'ink',
  'ink-hover',
  'muted',
  'signal',
  'signal-hover',
  'signal-soft',
  'ok',
  'warn',
  'bad',
  'info',
  'off',
  'synthetic-bg',
  'bad-soft',
  'warn-soft',
  'tag-bg',
  'bar-track',
  'acc-surveyed',
  'acc-verified',
  'acc-operator',
  'acc-approx',
  'valid',
  'invalid',
  'select-3d',
] as const

const ROWS = [
  { id: 'QC-01', reach: 38.5, height: 42.1 },
  { id: 'QC-02', reach: 36, height: 41.8 },
]

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title}>
      <SectionHeading className="text-ink">{title}</SectionHeading>
      <div className="flex flex-wrap items-center gap-3">{children}</div>
    </section>
  )
}

/** Local and dev only: every base component and variant. */
export function DesignSystemPage() {
  const [view, setView] = useState('split')
  const [tab, setTab] = useState('inspector')
  const [confirmOpen, setConfirmOpen] = useState(false)

  return (
    <main className="h-full overflow-auto bg-canvas p-8">
      <div className="flex items-center gap-2.5">
        <Logo />
        <h1 className="font-brand text-lg font-bold">Design system</h1>
      </div>

      <Section title="Colour tokens">
        <ul className="grid w-full grid-cols-6 gap-3">
          {TOKENS.map((name) => (
            <li key={name} className="rounded-xl bg-paper p-2 shadow-card">
              <div
                className="h-10 rounded-md ring-1 ring-line"
                style={{ background: `var(--${name})` }}
              />
              <p className="mt-1.5 font-mono">{name}</p>
            </li>
          ))}
        </ul>
      </Section>

      <Section title="Buttons">
        <Button>Default</Button>
        <Button variant="primary">Primary</Button>
        <Button variant="danger">Danger</Button>
        <Button variant="ghost">Ghost</Button>
        <Button size="sm">Small</Button>
        <Button disabled>Disabled</Button>
        <IconButton label="Layers">
          <Layers aria-hidden="true" />
        </IconButton>
        <IconButton label="Measure" pressed>
          <Ruler aria-hidden="true" />
        </IconButton>
        <Segmented
          label="View"
          value={view}
          onChange={setView}
          options={[
            { value: '2d', label: '2D' },
            { value: '3d', label: '3D' },
            { value: 'split', label: 'Split' },
          ]}
        />
      </Section>

      <Section title="Badges and tags">
        <Badge>Neutral</Badge>
        <Badge variant="synthetic">Synthetic</Badge>
        <Badge variant="ok">Ok</Badge>
        <Badge variant="warn">Warn</Badge>
        <Badge variant="bad">Bad</Badge>
        <NoSurveyBadge />
        <LockedBadge />
        <CountPill tone="bad">3</CountPill>
        <CountPill tone="warn">7</CountPill>
        <Tag>quay crane</Tag>
        <StatusDot status="ok" label="Online" />
        <StatusDot status="warn" label="Degraded" />
        <StatusDot status="bad" label="Down" />
        <StatusDot status="off" label="Offline" />
      </Section>

      <Section title="Tabs">
        <div className="w-[348px] rounded-xl bg-paper">
          <Tabs
            label="Demo panel"
            value={tab}
            onChange={setTab}
            tabs={[
              { value: 'inspector', label: 'Inspector' },
              { value: 'issues', label: 'Issues' },
              { value: 'versions', label: 'Versions' },
            ]}
          />
        </div>
      </Section>

      <Section title="Cards and data">
        <Card className="w-72">
          <KeyValue
            items={[
              ['Id', 'QC-01'],
              ['Position', '3.1042, 101.3921'],
              ['Heading', '90.0 deg'],
            ]}
          />
        </Card>
        <FloatingCard className="w-56">Floating readout: 12.4 m</FloatingCard>
        <div className="w-72 rounded-xl bg-paper p-2">
          <DataTable
            caption="Cranes"
            rows={ROWS}
            rowKey={(r) => r.id}
            columns={[
              { key: 'id', header: 'Id', render: (r) => r.id },
              { key: 'reach', header: 'Reach m', numeric: true, render: (r) => r.reach },
              { key: 'height', header: 'Height m', numeric: true, render: (r) => r.height },
            ]}
          />
        </div>
        <div className="w-48 space-y-2">
          <ProgressBar label="Capacity 40%" value={40} />
          <ProgressBar label="Capacity 95%" value={95} />
        </div>
      </Section>

      <Section title="Fields">
        <div className="flex flex-col gap-1">
          <Label htmlFor="ds-name">Name</Label>
          <Input id="ds-name" placeholder="Crane name" />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="ds-bad">Invalid</Label>
          <Input id="ds-bad" aria-invalid="true" defaultValue="-1" />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="ds-select">Type</Label>
          <Select id="ds-select" defaultValue="a">
            <option value="a">Quay crane</option>
            <option value="b">Yard crane</option>
          </Select>
        </div>
        <label className="flex items-center gap-1.5 text-xs">
          <input type="checkbox" defaultChecked /> Checkbox
        </label>
      </Section>

      <Section title="Overlays">
        <Tooltip title="Position" content="Surveyed to 5 cm">
          <Button>Tooltip</Button>
        </Tooltip>
        <Popover>
          <PopoverTrigger asChild>
            <Button>Popover</Button>
          </PopoverTrigger>
          <PopoverContent heading="Help">
            <TilesNote />
          </PopoverContent>
        </Popover>
        <Menu>
          <MenuTrigger asChild>
            <Button>Menu</Button>
          </MenuTrigger>
          <MenuContent>
            <MenuItem>Rename</MenuItem>
            <MenuItem>Duplicate</MenuItem>
          </MenuContent>
        </Menu>
        <Button onClick={() => setConfirmOpen(true)}>Confirm dialog</Button>
        <Button onClick={() => toast('Layout published')}>Toast</Button>
        <ConfirmDialog
          open={confirmOpen}
          title="Discard changes?"
          description="Unsaved edits to this layout will be lost."
          confirmLabel="Discard"
          danger
          onConfirm={() => setConfirmOpen(false)}
          onCancel={() => setConfirmOpen(false)}
        />
      </Section>

      <Section title="States">
        <div className="relative h-40 w-80 rounded-xl bg-canvas ring-1 ring-line">
          <LoadingCard subject="Westports" step="Layout" />
        </div>
        <div className="w-72 rounded-xl bg-paper">
          <SkeletonRows />
        </div>
        <div className="w-72 rounded-xl bg-paper">
          <EmptyState>Select something in the 3D view to see its details.</EmptyState>
        </div>
        <div className="w-72">
          <ErrorCard
            title="Layout unavailable"
            detail="The server did not answer."
            onRetry={() => {}}
          />
        </div>
        <div className="flex flex-col gap-2">
          <SaveStatus state={{ status: 'saving' }} onRetry={() => {}} />
          <SaveStatus state={{ status: 'saved' }} onRetry={() => {}} />
          <SaveStatus state={{ status: 'failed', reason: 'offline' }} onRetry={() => {}} />
        </div>
      </Section>
    </main>
  )
}
