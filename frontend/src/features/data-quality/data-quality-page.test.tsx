import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { basicCredential, useAuthStore, type Role } from '@/lib/stores/auth-store'
import { portFixture } from '@/test/fixtures'
import { server } from '@/test/setup'
import { DataQualityPage } from './data-quality-page'

vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { error: vi.fn(), dismiss: vi.fn(), loading: vi.fn() }),
}))

const BASE = 'http://api.test/api/v1/ports/WESTPORTS'
const CRANE = '0198a6f0-0000-7000-8000-000000000001'
const NOW = '2026-10-30T10:00:00Z'

const STALE = {
  id: 11,
  kind: 'STALE_EVENT',
  entityType: 'equipment',
  entityId: CRANE,
  eventId: '0198a6f0-0000-7000-8000-0000000000e1',
  eventType: 'equipment.position.observed',
  detail: {
    type: 'equipment.position.observed',
    source: 'SENSORS',
    occurredAt: '2026-10-30T08:00:00Z',
    currentSource: 'SENSORS',
    currentOccurredAt: '2026-10-30T08:10:00Z',
  },
  status: 'OPEN',
  createdAt: '2026-10-30T09:48:00Z',
  resolvedBy: null,
  resolvedAt: null,
  note: null,
}

const CONFLICT = {
  id: 12,
  kind: 'SOURCE_CONFLICT',
  entityType: 'vessel-call',
  entityId: '0198a6f0-0000-7000-8000-0000000000c1',
  eventId: '0198a6f0-0000-7000-8000-0000000000e2',
  eventType: 'vessel.call.updated',
  detail: {
    field: 'ata',
    applied: { source: 'edic', value: '2026-10-30T08:50:00Z' },
    other: { source: 'cbas', value: '2026-10-30T08:20:00Z' },
    differenceMinutes: 30,
  },
  status: 'OPEN',
  createdAt: '2026-10-30T09:00:00Z',
  resolvedBy: null,
  resolvedAt: null,
  note: null,
}

const LETTER = {
  id: 5,
  eventId: '0198a6f0-0000-7000-8000-0000000000e3',
  consumer: 'castor-core-projector',
  topic: 'castor.ingest.canonical.v1',
  partition: 0,
  offset: 41,
  reason: 'UNKNOWN_ENTITY',
  error: 'UNKNOWN_ENTITY: No cargo lot itap:COIL-3 is registered',
  attempts: 1,
  createdAt: '2026-10-30T09:30:00Z',
  replayOf: null,
  state: 'OPEN',
  replays: 0,
  lastReplayId: null,
  type: 'cargo.lot.moved',
  sourceSystem: 'ITAP',
  entityId: 'itap:COIL-3',
  event: {},
}

let requests: { method: string; url: string; body: unknown }[] = []

interface Served {
  items?: unknown[]
  letters?: unknown[]
}

function serve(options: Served = {}) {
  server.use(
    http.get(`${BASE.replace('/ports/WESTPORTS', '')}/ports/WESTPORTS`, () =>
      HttpResponse.json(portFixture),
    ),
    http.get(`${BASE}/reconciliation-items`, ({ request }) => {
      const url = new URL(request.url)
      requests.push({ method: 'GET', url: url.pathname + url.search, body: null })
      const kind = url.searchParams.get('kind')
      const all = options.items ?? [STALE, CONFLICT]
      return HttpResponse.json(
        kind ? all.filter((i) => (i as { kind: string }).kind === kind) : all,
      )
    }),
    http.get(`${BASE}/dead-letters`, () => HttpResponse.json(options.letters ?? [LETTER])),
  )
}

function signInAs(...roles: Role[]) {
  useAuthStore.getState().signIn({
    username: 'olivia',
    displayName: 'Olivia',
    roles,
    credential: basicCredential('olivia', 'secret'),
  })
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/data-quality']}>
        <DataQualityPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  requests = []
  vi.useFakeTimers({ toFake: ['Date'], now: new Date(NOW) })
  vi.mocked(toast).mockClear()
  vi.mocked(toast.error).mockClear()
  useAuthStore.getState().clearSession()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('Data quality page', () => {
  it('lists the open review items with kind, entity, event, detail and age', async () => {
    signInAs('OPERATOR')
    serve()
    renderPage()

    const table = await screen.findByRole('table', { name: /Open review items/ })
    const stale = within(table).getByRole('row', { name: /Stale event/ })
    expect(within(stale).getByText('equipment 0198a6f0...0001')).toBeInTheDocument()
    expect(within(stale).getByText('equipment.position.observed')).toBeInTheDocument()
    expect(
      within(stale).getByText(/older than the state held \(SENSORS, 2026-10-30T08:10:00Z\)/),
    ).toBeInTheDocument()
    expect(within(stale).getByText('12 min')).toBeInTheDocument()
    const conflict = within(table).getByRole('row', { name: /Source conflict/ })
    expect(within(conflict).getByText(/ata: edic 2026-10-30T08:50:00Z applied/)).toBeInTheDocument()
    expect(within(conflict).getByText('1 h')).toBeInTheDocument()
    expect(requests[0].url).toBe('/api/v1/ports/WESTPORTS/reconciliation-items?status=OPEN')
  })

  it('resolves an item with a note, and the note is required', async () => {
    signInAs('OPERATOR')
    serve()
    let sent: unknown = null
    server.use(
      http.post(`${BASE}/reconciliation-items/11/resolve`, async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json({ ...STALE, status: 'RESOLVED', resolvedBy: 'olivia' })
      }),
    )
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Resolve item 11' }))
    const drawer = screen.getByRole('dialog', { name: 'Resolve item 11' })
    const resolve = within(drawer).getByRole('button', { name: 'Resolve' })
    expect(resolve).toBeDisabled()

    await userEvent.type(within(drawer).getByLabelText('Note'), '   ')
    expect(resolve).toBeDisabled()
    await userEvent.type(within(drawer).getByLabelText('Note'), 'sensor clock was behind')
    expect(resolve).toBeEnabled()
    await userEvent.click(resolve)

    await waitFor(() => expect(sent).toEqual({ note: 'sensor clock was behind' }))
    await waitFor(() => expect(toast).toHaveBeenCalledWith('Item resolved'))
    expect(screen.queryByRole('dialog', { name: 'Resolve item 11' })).not.toBeInTheDocument()
  })

  it('shows the answer of the server when resolving is refused', async () => {
    signInAs('OPERATOR')
    serve()
    server.use(
      http.post(`${BASE}/reconciliation-items/11/resolve`, () =>
        HttpResponse.json(
          {
            type: 'conflict',
            title: 'Conflict',
            status: 409,
            detail: 'Reconciliation item 11 is already resolved',
          },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Resolve item 11' }))
    const drawer = screen.getByRole('dialog', { name: 'Resolve item 11' })
    await userEvent.type(within(drawer).getByLabelText('Note'), 'again')
    await userEvent.click(within(drawer).getByRole('button', { name: 'Resolve' }))

    expect(await within(drawer).findByRole('alert')).toHaveTextContent('already resolved')
  })

  it('filters by kind through the API and by text in the page', async () => {
    signInAs('OPERATOR')
    serve()
    renderPage()
    const table = await screen.findByRole('table', { name: /Open review items/ })
    expect(within(table).getAllByRole('row')).toHaveLength(3)

    await userEvent.selectOptions(screen.getByLabelText('Kind'), 'SOURCE_CONFLICT')
    await waitFor(() => expect(requests.at(-1)?.url).toContain('kind=SOURCE_CONFLICT'))
    await waitFor(() =>
      expect(
        within(screen.getByRole('table', { name: /Open review items/ })).getAllByRole('row'),
      ).toHaveLength(2),
    )

    await userEvent.type(screen.getByLabelText('Find'), 'nothing like this')
    expect(screen.getByText('No open item matches the filter.')).toBeInTheDocument()
  })

  it('says when nothing needs review', async () => {
    signInAs('OPERATOR')
    serve({ items: [], letters: [] })
    renderPage()

    expect(await screen.findByText(/Nothing needs review/)).toBeInTheDocument()
    expect(await screen.findByText(/No event was set aside/)).toBeInTheDocument()
  })

  describe('Dead letters', () => {
    it('lists them for an operator, who cannot replay', async () => {
      signInAs('OPERATOR')
      serve()
      renderPage()

      const table = await screen.findByRole('table', { name: /Dead letters/ })
      const row = within(table).getByRole('row', { name: /UNKNOWN_ENTITY/ })
      expect(within(row).getByText('cargo.lot.moved')).toBeInTheDocument()
      expect(within(row).getByText('No cargo lot itap:COIL-3 is registered')).toBeInTheDocument()
      expect(within(row).getByText('Not replayed')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Replay dead letter/ })).not.toBeInTheDocument()
      expect(screen.getByText(/an administrator replays dead letters/)).toBeInTheDocument()
    })

    it('lets an admin replay one after a confirmation, and says it was applied', async () => {
      signInAs('ADMIN')
      serve()
      let replayed = 0
      server.use(
        http.post(`${BASE}/dead-letters/5/replay`, () => {
          replayed += 1
          return HttpResponse.json({
            outcome: 'APPLIED',
            attempt: {
              ...LETTER,
              id: 6,
              replayOf: 5,
              reason: 'REPLAYED',
              error: 'REPLAYED: applied',
              state: 'REPLAYED',
            },
          })
        }),
      )
      renderPage()

      await userEvent.click(await screen.findByRole('button', { name: 'Replay dead letter 5' }))
      expect(replayed).toBe(0)
      await userEvent.click(
        within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Replay' }),
      )

      await waitFor(() => expect(replayed).toBe(1))
      await waitFor(() => expect(toast).toHaveBeenCalledWith('Dead letter 5 replayed and applied'))
    })

    it('says so when the replay fails again', async () => {
      signInAs('ADMIN')
      serve()
      server.use(
        http.post(`${BASE}/dead-letters/5/replay`, () =>
          HttpResponse.json({
            outcome: 'FAILED',
            attempt: {
              ...LETTER,
              id: 6,
              replayOf: 5,
              error: 'UNKNOWN_ENTITY: No cargo lot itap:COIL-3 is registered',
              state: 'REPLAY_FAILED',
            },
          }),
        ),
      )
      renderPage()

      await userEvent.click(await screen.findByRole('button', { name: 'Replay dead letter 5' }))
      await userEvent.click(
        within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Replay' }),
      )

      await waitFor(() =>
        expect(toast.error).toHaveBeenCalledWith(
          'Dead letter 5 failed again: No cargo lot itap:COIL-3 is registered',
        ),
      )
    })

    it('shows the state of earlier replays and does not offer a replay once one was applied', async () => {
      signInAs('ADMIN')
      serve({ letters: [{ ...LETTER, state: 'REPLAYED', replays: 2, lastReplayId: 7 }] })
      renderPage()

      const row = await screen.findByRole('row', { name: /UNKNOWN_ENTITY/ })
      expect(within(row).getByText('Replayed, applied (2)')).toBeInTheDocument()
      expect(within(row).getByRole('button', { name: 'Replay dead letter 5' })).toBeDisabled()
    })
  })
})
