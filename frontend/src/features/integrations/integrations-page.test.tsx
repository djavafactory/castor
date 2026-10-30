import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { toast } from 'sonner'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { basicCredential, useAuthStore, type Role } from '@/lib/stores/auth-store'
import { portFixture } from '@/test/fixtures'
import { server } from '@/test/setup'
import { IntegrationsPage } from './integrations-page'

vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { error: vi.fn(), dismiss: vi.fn(), loading: vi.fn() }),
}))

const BASE = 'http://api.test/api/v1/ports/WESTPORTS'
const ORIGINAL = '0198a6f0-0000-7000-8000-000000000001'
const REPLAY = '0198a6f0-0000-7000-8000-000000000002'

const SOURCES = [
  {
    code: 'cbas',
    enabled: true,
    requireSignature: false,
    qualityDefault: 'SIMULATED',
    activeKeys: 1,
    accepted: 12,
    duplicate: 3,
    quarantined: 2,
    lastReceivedAt: '2026-10-29T08:00:00Z',
    freshness: 'FRESH',
    freshnessSlaSeconds: 900,
  },
  {
    code: 'sensors',
    enabled: true,
    requireSignature: false,
    qualityDefault: 'SIMULATED',
    activeKeys: 1,
    accepted: 0,
    duplicate: 0,
    quarantined: 0,
    lastReceivedAt: null,
    freshness: 'UNKNOWN',
    freshnessSlaSeconds: 900,
  },
  {
    code: 'edic',
    enabled: false,
    requireSignature: false,
    qualityDefault: 'SIMULATED',
    activeKeys: 0,
    accepted: 5,
    duplicate: 0,
    quarantined: 0,
    lastReceivedAt: '2026-10-28T08:00:00Z',
    freshness: 'STALE',
    freshnessSlaSeconds: 7200,
  },
]

const ENTRY = {
  id: 7,
  receptionId: ORIGINAL,
  source: 'cbas',
  sourceEventId: 'CBAS-2026-000417',
  reasonCode: 'UNKNOWN_MAPPING',
  detail: "No mapping for cbas BERTH 'B9' at 2026-10-29T06:20:00Z",
  receivedAt: '2026-10-29T08:00:00Z',
  quarantinedAt: '2026-10-29T08:00:01Z',
  replayOf: null,
  replays: 0,
  latestReplayStatus: null,
  subjectType: 'BERTH',
  subjectId: 'B9',
}

const DETAIL = {
  entry: ENTRY,
  raw: { msgId: 'CBAS-2026-000417', berthRef: 'B9', apiKey: '[REDACTED]' },
  rawAvailable: true,
  attempts: [],
}

const MAPPING = {
  id: 3,
  sourceSystem: 'cbas',
  sourceEntityType: 'BERTH',
  sourceId: 'B3',
  canonicalId: 'asset-berth-b3',
  effectiveFrom: '1970-01-01T00:00:00Z',
  effectiveTo: null,
  createdBy: 'seed',
  createdAt: '2026-10-29T08:00:00Z',
}

let requests: { method: string; url: string; body: unknown }[] = []

function record(request: Request, body: unknown = null) {
  requests.push({
    method: request.method,
    url: new URL(request.url).pathname + new URL(request.url).search,
    body,
  })
}

interface Served {
  sources?: unknown[]
  quarantine?: unknown[]
  mappings?: unknown[]
  unresolved?: unknown[]
  detail?: unknown
  keys?: unknown[]
}

function serve(options: Served = {}) {
  server.use(
    http.get(`${BASE.replace('/ports/WESTPORTS', '')}/ports/WESTPORTS`, () =>
      HttpResponse.json(portFixture),
    ),
    http.get(`${BASE}/integrations/sources`, () =>
      HttpResponse.json({ items: options.sources ?? SOURCES }),
    ),
    http.get(`${BASE}/integrations/quarantine`, ({ request }) => {
      record(request)
      return HttpResponse.json({ items: options.quarantine ?? [ENTRY], nextBefore: null })
    }),
    http.get(`${BASE}/integrations/quarantine/:id`, () =>
      HttpResponse.json(options.detail ?? DETAIL),
    ),
    http.get(`${BASE}/integrations/mappings/unresolved`, () =>
      HttpResponse.json({
        items: options.unresolved ?? [
          {
            source: 'cbas',
            entityType: 'BERTH',
            sourceId: 'B9',
            reasonCode: 'UNKNOWN_MAPPING',
            records: 4,
            lastSeenAt: '2026-10-29T08:00:01Z',
          },
        ],
      }),
    ),
    http.get(`${BASE}/integrations/mappings`, ({ request }) => {
      record(request)
      return HttpResponse.json({ items: options.mappings ?? [MAPPING] })
    }),
    http.get(`${BASE}/integrations/sources/:source/keys`, () =>
      HttpResponse.json({
        items: options.keys ?? [
          {
            id: 9,
            prefix: 'ck_abcde',
            origin: 'issued',
            createdAt: '2026-10-29T08:00:00Z',
            revokedAt: null,
          },
          {
            id: 1,
            prefix: 'ck_envke',
            origin: 'env',
            createdAt: '2026-10-28T08:00:00Z',
            revokedAt: null,
          },
        ],
      }),
    ),
  )
}

function signInAs(...roles: Role[]) {
  useAuthStore.getState().signIn({
    username: 'ada',
    displayName: 'Ada',
    roles,
    credential: basicCredential('ada', 'secret'),
  })
}

function renderPage(path = '/integrations') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <IntegrationsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  requests = []
  vi.mocked(toast).mockClear()
  vi.mocked(toast.error).mockClear()
  useAuthStore.getState().clearSession()
})

describe('Integrations page', () => {
  it('has the three tabs and starts on Sources', async () => {
    signInAs('OPERATOR')
    serve()
    renderPage()

    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual([
      'Sources',
      'Quarantine',
      'Mappings',
    ])
    expect(screen.getByRole('tab', { name: 'Sources' })).toHaveAttribute('aria-selected', 'true')
    expect(
      await screen.findByRole('table', { name: /Sources and their counters/ }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Monitor' })).toHaveAttribute('href', '/')
  })

  it('opens on the tab named in the address', async () => {
    signInAs('OPERATOR')
    serve()
    renderPage('/integrations?tab=quarantine')

    expect(screen.getByRole('tab', { name: 'Quarantine' })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByRole('table', { name: /Quarantined records/ })).toBeInTheDocument()
  })

  describe('Sources', () => {
    it('shows the counters, the freshness and when each source last sent something', async () => {
      signInAs('OPERATOR')
      serve()
      renderPage()

      const table = await screen.findByRole('table', { name: /Sources and their counters/ })
      const cbas = within(table).getByRole('row', { name: /cbas/ })
      expect(within(cbas).getByText('Fresh')).toBeInTheDocument()
      expect(within(cbas).getByText('2026-10-29 08:00:00 UTC')).toBeInTheDocument()
      expect(within(cbas).getByText('12')).toBeInTheDocument()
      expect(within(cbas).getByText('3')).toBeInTheDocument()
      const sensors = within(table).getByRole('row', { name: /sensors/ })
      expect(within(sensors).getByText('Never seen')).toBeInTheDocument()
      expect(within(sensors).getByText('Never')).toBeInTheDocument()
      const edic = within(table).getByRole('row', { name: /edic/ })
      expect(within(edic).getByText('Stale')).toHaveAttribute('title', 'Limit 2 h')
      expect(within(edic).getByText('Off')).toBeInTheDocument()
    })

    it('filters by freshness and by name and says when nothing matches', async () => {
      signInAs('OPERATOR')
      serve()
      renderPage()
      await screen.findByRole('table', { name: /Sources and their counters/ })

      await userEvent.selectOptions(screen.getByLabelText('Freshness'), 'STALE')
      expect(screen.getAllByRole('row')).toHaveLength(2)
      expect(screen.getByText('edic')).toBeInTheDocument()

      await userEvent.type(screen.getByLabelText('Source'), 'cbas')
      expect(screen.getByText('No source matches the filter.')).toBeInTheDocument()
    })

    it('says so when the port has no source', async () => {
      signInAs('OPERATOR')
      serve({ sources: [] })
      renderPage()

      expect(await screen.findByText('No source is configured for this port.')).toBeInTheDocument()
    })

    it('shows the problem with Retry when the gateway cannot be reached', async () => {
      signInAs('OPERATOR')
      server.use(
        http.get(`${BASE}/integrations/sources`, () =>
          HttpResponse.json(
            { title: 'Ingest gateway unavailable', status: 502, type: 'x' },
            { status: 502, headers: { 'Content-Type': 'application/problem+json' } },
          ),
        ),
      )
      vi.spyOn(console, 'error').mockImplementation(() => {})
      renderPage()

      expect(await screen.findByText('Ingest gateway unavailable')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    })

    it('jumps to the quarantine of a source from its count', async () => {
      signInAs('OPERATOR')
      serve()
      renderPage()

      await userEvent.click(
        await screen.findByRole('button', { name: '2 quarantined from cbas: show them' }),
      )

      expect(screen.getByRole('tab', { name: 'Quarantine' })).toHaveAttribute(
        'aria-selected',
        'true',
      )
      expect(screen.getByLabelText('Source')).toHaveValue('cbas')
      await screen.findByRole('table', { name: /Quarantined records/ })
      expect(requests.at(-1)?.url).toContain('source=cbas')
    })

    it('lets an operator see the keys but not change them', async () => {
      signInAs('OPERATOR')
      serve()
      renderPage()

      await userEvent.click(await screen.findByRole('button', { name: 'Keys of cbas' }))

      const drawer = await screen.findByRole('dialog', { name: 'API keys of cbas' })
      expect(await within(drawer).findByText('ck_abcde...')).toBeInTheDocument()
      expect(within(drawer).queryByRole('button', { name: 'Create key' })).not.toBeInTheDocument()
      expect(within(drawer).queryByRole('button', { name: /Rotate/ })).not.toBeInTheDocument()
      expect(within(drawer).queryByRole('button', { name: /Revoke/ })).not.toBeInTheDocument()
      expect(screen.getByRole('note')).toHaveTextContent('View only')
    })

    it('creates a key and shows it once, after Create key', async () => {
      signInAs('ADMIN')
      serve()
      server.use(
        http.post(`${BASE}/integrations/sources/cbas/keys`, ({ request }) => {
          record(request)
          return HttpResponse.json(
            {
              id: 10,
              prefix: 'ck_NEWKE',
              origin: 'issued',
              createdAt: '2026-10-29T09:00:00Z',
              key: 'ck_NEWKEY-secret-value',
            },
            { status: 201 },
          )
        }),
      )
      renderPage()
      await userEvent.click(await screen.findByRole('button', { name: 'Keys of cbas' }))

      await userEvent.click(await screen.findByRole('button', { name: 'Create key' }))

      expect(await screen.findByTestId('new-key')).toHaveTextContent('ck_NEWKEY-secret-value')
      expect(requests.at(-1)).toMatchObject({
        method: 'POST',
        url: '/api/v1/ports/WESTPORTS/integrations/sources/cbas/keys',
      })
      // Closing the drawer drops the secret: it is not in the page any more
      await userEvent.click(screen.getByRole('button', { name: 'Close' }))
      expect(screen.queryByText('ck_NEWKEY-secret-value')).not.toBeInTheDocument()
    })

    it('rotates and revokes an issued key after a confirmation, and leaves the environment key alone', async () => {
      signInAs('ADMIN')
      serve()
      server.use(
        http.post(`${BASE}/integrations/sources/cbas/keys/9/rotate`, ({ request }) => {
          record(request)
          return HttpResponse.json(
            {
              id: 11,
              prefix: 'ck_ROTAT',
              origin: 'issued',
              createdAt: '2026-10-29T09:00:00Z',
              key: 'ck_ROTATED-secret',
            },
            { status: 201 },
          )
        }),
        http.delete(`${BASE}/integrations/sources/cbas/keys/9`, ({ request }) => {
          record(request)
          return HttpResponse.json({
            id: 9,
            prefix: 'ck_abcde',
            origin: 'issued',
            createdAt: '2026-10-29T08:00:00Z',
            revokedAt: '2026-10-29T10:00:00Z',
          })
        }),
      )
      renderPage()
      await userEvent.click(await screen.findByRole('button', { name: 'Keys of cbas' }))
      await screen.findByText('ck_abcde...')
      expect(screen.getAllByRole('button', { name: /Rotate key/ })).toHaveLength(1)

      await userEvent.click(screen.getByRole('button', { name: 'Rotate key ck_abcde' }))
      const dialog = await screen.findByRole('alertdialog')
      expect(dialog).toHaveTextContent('stops working at once')
      await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
      expect(requests).toHaveLength(0)

      await userEvent.click(screen.getByRole('button', { name: 'Rotate key ck_abcde' }))
      await userEvent.click(
        within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Rotate key' }),
      )
      expect(await screen.findByTestId('new-key')).toHaveTextContent('ck_ROTATED-secret')

      await userEvent.click(screen.getByRole('button', { name: 'Revoke key ck_abcde' }))
      await userEvent.click(
        within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Revoke key' }),
      )
      await waitFor(() => expect(requests.map((r) => r.method)).toEqual(['POST', 'DELETE']))
    })
  })

  describe('Quarantine', () => {
    it('lists what was refused with its reason and filters it', async () => {
      signInAs('OPERATOR')
      serve()
      renderPage('/integrations?tab=quarantine')

      const table = await screen.findByRole('table', { name: /Quarantined records/ })
      const row = within(table).getByRole('row', { name: /CBAS-2026-000417/ })
      expect(within(row).getByText('UNKNOWN_MAPPING')).toBeInTheDocument()
      expect(within(row).getByText('B9')).toBeInTheDocument()
      expect(within(row).getByText('Not replayed')).toBeInTheDocument()

      await userEvent.selectOptions(screen.getByLabelText('Reason'), 'SCHEMA_INVALID')
      await waitFor(() => expect(requests.at(-1)?.url).toContain('reason=SCHEMA_INVALID'))
      await userEvent.type(screen.getByLabelText('Find'), 'nothing like this')
      expect(
        await screen.findByText('No quarantined record matches the filter.'),
      ).toBeInTheDocument()
    })

    it('says that nothing is quarantined, and offers older records when there are more', async () => {
      signInAs('OPERATOR')
      serve({ quarantine: [] })
      renderPage('/integrations?tab=quarantine')

      expect(await screen.findByText(/Nothing is quarantined/)).toBeInTheDocument()
    })

    it('loads the next page with the keyset of the last one', async () => {
      signInAs('OPERATOR')
      serve()
      server.use(
        http.get(`${BASE}/integrations/quarantine`, ({ request }) => {
          record(request)
          const before = new URL(request.url).searchParams.get('before')
          return before
            ? HttpResponse.json({
                items: [{ ...ENTRY, id: 5, receptionId: REPLAY, sourceEventId: 'OLDER-1' }],
                nextBefore: null,
              })
            : HttpResponse.json({ items: [ENTRY], nextBefore: 7 })
        }),
      )
      renderPage('/integrations?tab=quarantine')

      await userEvent.click(await screen.findByRole('button', { name: 'Load older records' }))

      expect(await screen.findByText('OLDER-1')).toBeInTheDocument()
      expect(requests.at(-1)?.url).toContain('before=7')
      expect(screen.queryByRole('button', { name: 'Load older records' })).not.toBeInTheDocument()
    })

    it('shows the record read-only, with its reason, payload and no way to act for an operator', async () => {
      signInAs('OPERATOR')
      serve()
      renderPage('/integrations?tab=quarantine')

      await userEvent.click(
        await screen.findByRole('button', { name: /Open record CBAS-2026-000417/ }),
      )

      const drawer = await screen.findByRole('dialog', { name: 'Quarantined record' })
      expect(await within(drawer).findByText(/Add the mapping, then replay/)).toBeInTheDocument()
      expect(within(drawer).getByText(DETAIL.entry.detail)).toBeInTheDocument()
      const payload = within(drawer).getByLabelText('Payload (read-only)')
      expect(payload).toHaveTextContent('"berthRef": "B9"')
      expect(payload).toHaveTextContent('"apiKey": "[REDACTED]"')
      expect(within(drawer).queryByRole('button', { name: 'Replay' })).not.toBeInTheDocument()
      expect(within(drawer).queryByRole('button', { name: /Add mapping/ })).not.toBeInTheDocument()
      expect(within(drawer).getByText('Not replayed.')).toBeInTheDocument()
    })

    it('replays after a confirmation and shows the attempts that follow', async () => {
      signInAs('ADMIN')
      serve()
      server.use(
        http.post(`${BASE}/integrations/quarantine/${ORIGINAL}/replay`, ({ request }) => {
          record(request)
          return HttpResponse.json({ receptionId: REPLAY, replayOf: ORIGINAL }, { status: 201 })
        }),
      )
      renderPage('/integrations?tab=quarantine')
      await userEvent.click(
        await screen.findByRole('button', { name: /Open record CBAS-2026-000417/ }),
      )
      const drawer = await screen.findByRole('dialog', { name: 'Quarantined record' })

      await userEvent.click(await within(drawer).findByRole('button', { name: 'Replay' }))
      const dialog = await screen.findByRole('alertdialog')
      expect(dialog).toHaveTextContent('stay as they are')
      // The detail now has the replay in it: the page asks again after the action
      server.use(
        http.get(`${BASE}/integrations/quarantine/${ORIGINAL}`, () =>
          HttpResponse.json({
            ...DETAIL,
            entry: { ...ENTRY, replays: 1, latestReplayStatus: 'NORMALIZED' },
            attempts: [
              {
                receptionId: REPLAY,
                receivedAt: '2026-10-29T09:00:00Z',
                status: 'NORMALIZED',
                reasonCode: null,
                detail: null,
                events: 1,
                published: 1,
              },
            ],
          }),
        ),
      )
      await userEvent.click(within(dialog).getByRole('button', { name: 'Replay' }))

      await waitFor(() => expect(toast).toHaveBeenCalledWith('Replay queued'))
      expect(requests.find((r) => r.method === 'POST')?.url).toBe(
        `/api/v1/ports/WESTPORTS/integrations/quarantine/${ORIGINAL}/replay`,
      )
      expect(await within(drawer).findByText('Accepted')).toBeInTheDocument()
      expect(within(drawer).getByText('1 of 1 published')).toBeInTheDocument()
    })

    it('reports why a replay was refused', async () => {
      signInAs('ADMIN')
      serve()
      server.use(
        http.post(`${BASE}/integrations/quarantine/${ORIGINAL}/replay`, () =>
          HttpResponse.json(
            {
              type: 'x',
              title: 'Conflict',
              status: 409,
              detail: 'Replay abc of this record has not been processed yet',
            },
            { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
          ),
        ),
      )
      vi.spyOn(console, 'warn').mockImplementation(() => {})
      renderPage('/integrations?tab=quarantine')
      await userEvent.click(
        await screen.findByRole('button', { name: /Open record CBAS-2026-000417/ }),
      )
      await userEvent.click(await screen.findByRole('button', { name: 'Replay' }))
      await userEvent.click(
        within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Replay' }),
      )

      await waitFor(() =>
        expect(toast.error).toHaveBeenCalledWith(
          'Conflict: Replay abc of this record has not been processed yet',
        ),
      )
    })

    it('cannot replay a record whose payload was removed', async () => {
      signInAs('ADMIN')
      serve({ detail: { ...DETAIL, raw: null, rawAvailable: false } })
      renderPage('/integrations?tab=quarantine')

      await userEvent.click(
        await screen.findByRole('button', { name: /Open record CBAS-2026-000417/ }),
      )

      const drawer = await screen.findByRole('dialog', { name: 'Quarantined record' })
      expect(await within(drawer).findByRole('button', { name: 'Replay' })).toBeDisabled()
      expect(within(drawer).getByRole('note')).toHaveTextContent('cannot be replayed')
    })

    it('links a refused replay to its original and waits for a replay in flight', async () => {
      signInAs('ADMIN')
      const replayEntry = { ...ENTRY, id: 8, receptionId: REPLAY, replayOf: ORIGINAL }
      serve({
        quarantine: [replayEntry, ENTRY],
        detail: {
          entry: replayEntry,
          raw: { msgId: 'x' },
          rawAvailable: true,
          attempts: [
            {
              receptionId: REPLAY,
              receivedAt: '2026-10-29T09:00:00Z',
              status: 'QUARANTINED',
              reasonCode: 'UNKNOWN_MAPPING',
              detail: 'still no mapping',
              events: 0,
              published: 0,
            },
            {
              receptionId: '0198a6f0-0000-7000-8000-000000000003',
              receivedAt: '2026-10-29T09:05:00Z',
              status: 'RECEIVED',
              events: 0,
              published: 0,
            },
          ],
        },
      })
      renderPage('/integrations?tab=quarantine')
      const table = await screen.findByRole('table', { name: /Quarantined records/ })
      expect(within(table).getByText('Replay, refused again')).toBeInTheDocument()

      await userEvent.click(within(table).getAllByRole('button', { name: /Open record/ })[0])

      const drawer = await screen.findByRole('dialog', { name: 'Quarantined record' })
      expect(
        await within(drawer).findByRole('button', { name: 'Open the original record' }),
      ).toBeInTheDocument()
      expect(within(drawer).getByText(/\(this one\)/)).toBeInTheDocument()
      expect(within(drawer).getByText('Waiting')).toBeInTheDocument()
      expect(within(drawer).getByRole('button', { name: 'Replay' })).toBeDisabled()
    })

    it('goes from a quarantined record to a new mapping for its source id', async () => {
      signInAs('ADMIN')
      serve()
      renderPage('/integrations?tab=quarantine')
      await userEvent.click(
        await screen.findByRole('button', { name: /Open record CBAS-2026-000417/ }),
      )

      await userEvent.click(await screen.findByRole('button', { name: 'Add mapping for B9' }))

      expect(screen.getByRole('tab', { name: 'Mappings' })).toHaveAttribute('aria-selected', 'true')
      const form = await screen.findByRole('dialog', { name: 'New identity mapping' })
      expect(within(form).getByLabelText('Source id')).toHaveValue('B9')
      expect(within(form).getByLabelText('Source system')).toHaveValue('cbas')
      expect(within(form).getByLabelText('Kind of thing')).toHaveValue('BERTH')
    })
  })

  describe('Mappings', () => {
    it('lists the source ids that wait for a mapping with their counts, and the mappings', async () => {
      signInAs('OPERATOR')
      serve()
      renderPage('/integrations?tab=mappings')

      const waiting = await screen.findByRole('table', {
        name: /Source ids that quarantined records/,
      })
      const row = within(waiting).getByRole('row', { name: /B9/ })
      expect(within(row).getByText('UNKNOWN_MAPPING')).toBeInTheDocument()
      expect(
        within(row).getByRole('button', { name: /4 quarantined records of B9/ }),
      ).toBeInTheDocument()
      const table = await screen.findByRole('table', { name: 'Identity mappings' })
      expect(within(table).getByText('asset-berth-b3')).toBeInTheDocument()
      expect(within(table).getByText('Open')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'New mapping' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Close the mapping/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Add a mapping for/ })).not.toBeInTheDocument()
    })

    it('says when no id waits and when nothing is mapped yet', async () => {
      signInAs('OPERATOR')
      serve({ unresolved: [], mappings: [] })
      renderPage('/integrations?tab=mappings')

      expect(await screen.findByText('No source id is waiting for a mapping.')).toBeInTheDocument()
      await userEvent.selectOptions(screen.getByLabelText('State'), 'all')
      expect(await screen.findByText('There is no identity mapping yet.')).toBeInTheDocument()
    })

    it('filters the mappings on the server', async () => {
      signInAs('OPERATOR')
      serve()
      renderPage('/integrations?tab=mappings')
      await screen.findByRole('table', { name: 'Identity mappings' })
      expect(requests.at(-1)?.url).toContain('state=open')

      await userEvent.selectOptions(screen.getByLabelText('Source'), 'itap')
      await userEvent.selectOptions(screen.getByLabelText('Kind'), 'STORAGE')
      await userEvent.selectOptions(screen.getByLabelText('State'), 'all')
      await userEvent.type(screen.getByLabelText('Source id'), 'PAD')

      await waitFor(() => {
        const url = requests.at(-1)?.url ?? ''
        expect(url).toContain('source=itap')
        expect(url).toContain('entityType=STORAGE')
        expect(url).toContain('sourceId=PAD')
        expect(url).not.toContain('state=')
      })
    })

    it('adds a mapping for a waiting id and sends what was typed, in UTC', async () => {
      signInAs('ADMIN')
      serve()
      server.use(
        http.post(`${BASE}/integrations/mappings`, async ({ request }) => {
          record(request, await request.json())
          return HttpResponse.json(
            { ...MAPPING, id: 4, sourceId: 'B9', canonicalId: 'asset-b9' },
            { status: 201 },
          )
        }),
      )
      renderPage('/integrations?tab=mappings')

      await userEvent.click(await screen.findByRole('button', { name: 'Add a mapping for B9' }))
      const form = await screen.findByRole('dialog', { name: 'New identity mapping' })
      expect(within(form).getByLabelText('Source id')).toHaveValue('B9')
      await userEvent.type(within(form).getByLabelText('Canonical id'), ' asset-b9 ')
      await userEvent.type(within(form).getByLabelText('Valid from (UTC)'), '2026-10-29T06:00')
      await userEvent.click(within(form).getByRole('button', { name: 'Add mapping' }))

      await waitFor(() => expect(toast).toHaveBeenCalledWith('Mapping added for B9'))
      expect(requests.find((r) => r.method === 'POST')?.body).toEqual({
        sourceSystem: 'cbas',
        sourceEntityType: 'BERTH',
        sourceId: 'B9',
        canonicalId: 'asset-b9',
        effectiveFrom: '2026-10-29T06:00:00.000Z',
      })
      expect(screen.queryByRole('dialog', { name: 'New identity mapping' })).not.toBeInTheDocument()
    })

    it('checks the form before it asks the server and shows what the server refuses', async () => {
      signInAs('ADMIN')
      serve()
      server.use(
        http.post(`${BASE}/integrations/mappings`, () =>
          HttpResponse.json(
            {
              type: 'x',
              title: 'Conflict',
              status: 409,
              detail: 'Another mapping of cbas BERTH B3 is valid in that period: close it first',
            },
            { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
          ),
        ),
      )
      vi.spyOn(console, 'warn').mockImplementation(() => {})
      renderPage('/integrations?tab=mappings')
      await userEvent.click(await screen.findByRole('button', { name: 'New mapping' }))
      const form = await screen.findByRole('dialog', { name: 'New identity mapping' })

      await userEvent.click(within(form).getByRole('button', { name: 'Add mapping' }))
      expect(within(form).getByRole('alert')).toHaveTextContent(
        'The source id and the canonical id are required.',
      )

      await userEvent.type(within(form).getByLabelText('Source id'), 'B3')
      await userEvent.type(within(form).getByLabelText('Canonical id'), 'asset-3')
      await userEvent.type(within(form).getByLabelText('Valid from (UTC)'), '2026-10-29T10:00')
      await userEvent.type(within(form).getByLabelText('Valid until (UTC)'), '2026-10-29T09:00')
      await userEvent.click(within(form).getByRole('button', { name: 'Add mapping' }))
      expect(within(form).getByRole('alert')).toHaveTextContent('The end must be after the start.')
      expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0)

      await userEvent.clear(within(form).getByLabelText('Valid until (UTC)'))
      await userEvent.click(within(form).getByRole('button', { name: 'Add mapping' }))
      expect(await within(form).findByRole('alert')).toHaveTextContent(
        'Conflict: Another mapping of cbas BERTH B3 is valid in that period: close it first',
      )
    })

    it('closes an open mapping after a confirmation', async () => {
      signInAs('ADMIN')
      serve()
      server.use(
        http.post(`${BASE}/integrations/mappings/3/close`, async ({ request }) => {
          record(request, await request.json())
          return HttpResponse.json({ ...MAPPING, effectiveTo: '2026-10-29T10:00:00Z' })
        }),
      )
      renderPage('/integrations?tab=mappings')

      await userEvent.click(await screen.findByRole('button', { name: 'Close the mapping of B3' }))
      const dialog = await screen.findByRole('alertdialog')
      expect(dialog).toHaveTextContent('Events of an earlier time still map to it')
      await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
      expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0)

      await userEvent.click(screen.getByRole('button', { name: 'Close the mapping of B3' }))
      await userEvent.click(
        within(await screen.findByRole('alertdialog')).getByRole('button', {
          name: 'Close mapping',
        }),
      )

      await waitFor(() => expect(toast).toHaveBeenCalledWith('Mapping of B3 closed'))
      expect(requests.find((r) => r.method === 'POST')?.body).toEqual({})
    })
  })
})
