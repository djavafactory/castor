import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { saveTextFile } from '@/lib/download'
import type { Role } from '@/lib/stores/auth-store'
import { useAuthStore } from '@/lib/stores/auth-store'
import { useEditorStore } from '@/lib/stores/editor-store'
import { layoutWire } from '@/test/fixtures'
import { server } from '@/test/setup'
import { runUndo } from '../history/undo-redo'
import { GeoJsonExchange } from './geojson-exchange'

vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { error: vi.fn(), dismiss: vi.fn(), loading: vi.fn() }),
}))
vi.mock('@/lib/download', () => ({ saveTextFile: vi.fn() }))

const BASE = 'http://api.test/api/v1'
const PORT = `${BASE}/ports/WESTPORTS`

const asset = (id: string, rowVersion: number, name: string) => ({
  id,
  typeCode: 'WAREHOUSE',
  rowVersion,
  name,
  anchor: [101.3, 2.95],
  lengthM: 80,
})

const report = (overrides: Record<string, unknown> = {}) => ({
  file: 'shapes.geojson',
  added: 1,
  updated: 1,
  skipped: 1,
  skipReasons: ['Bow tie: The shape crosses itself'],
  roundTrip: { coordinates: 12, maxDifferenceMm: 0.4567 },
  ...overrides,
})

let imports: string[] = []
let stored = false
let requests: { method: string; url: string; ifMatch: string | null; body: unknown }[] = []

function serve(importReport = report()) {
  server.use(
    http.get(`${PORT}/layouts/draft`, () =>
      HttpResponse.json(
        layoutWire({
          versionId: 'd-4',
          number: 4,
          status: 'DRAFT',
          assets: stored
            ? [asset('a-1', 2, 'Hall renamed'), asset('a-2', 0, 'Hall 2')]
            : [asset('a-1', 1, 'Hall 1')],
        }),
      ),
    ),
    http.post(`${BASE}/layouts/d-4/import`, ({ request }) => {
      const dryRun = new URL(request.url).searchParams.get('dryRun')
      imports.push(`dryRun=${dryRun}`)
      if (dryRun === 'false') stored = true
      return HttpResponse.json(importReport)
    }),
    http.delete(`${BASE}/layouts/d-4/assets/:id`, ({ request, params }) => {
      requests.push({
        method: 'DELETE',
        url: String(params.id),
        ifMatch: request.headers.get('If-Match'),
        body: null,
      })
      return new HttpResponse(null, { status: 204 })
    }),
    http.patch(`${BASE}/layouts/d-4/assets/:id`, async ({ request, params }) => {
      requests.push({
        method: 'PATCH',
        url: String(params.id),
        ifMatch: request.headers.get('If-Match'),
        body: await request.json(),
      })
      return HttpResponse.json({ feature: {}, warnings: [] })
    }),
  )
}

function renderExchange(roles: Role[] = ['EDITOR']) {
  useAuthStore.setState({ username: 'ed', displayName: 'Ed', roles, credential: 'Basic x' })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <GeoJsonExchange draftId="d-4" disabled={false} />
    </QueryClientProvider>,
  )
}

const file = (name = 'shapes.geojson') =>
  new File(['{"type":"FeatureCollection","features":[]}'], name, { type: 'application/geo+json' })

async function choose(name?: string) {
  await userEvent.upload(screen.getByLabelText('GeoJSON file'), file(name))
}

beforeEach(() => {
  imports = []
  stored = false
  requests = []
  vi.mocked(toast).mockClear()
  vi.mocked(toast.error).mockClear()
  vi.mocked(saveTextFile).mockClear()
  useEditorStore.getState().reset()
})

describe('GeoJSON section', () => {
  it('explains what export and import do', () => {
    serve()
    renderExchange()

    expect(
      screen.getByText(
        'Export writes every shape as [longitude, latitude] with its provenance. Import adds or updates catalogue assets; shapes that overlap, cross themselves or fall in a restricted zone are skipped.',
      ),
    ).toBeInTheDocument()
  })

  it('offers import to editors only', () => {
    serve()
    renderExchange(['VIEWER'])

    expect(screen.getByRole('button', { name: 'Export GeoJSON' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Import GeoJSON' })).not.toBeInTheDocument()
  })
})

describe('GeoJSON export', () => {
  it('saves the file under the name the server gives and notes it in the history', async () => {
    server.use(
      http.get(`${BASE}/layouts/d-4/export`, () =>
        HttpResponse.json(
          { type: 'FeatureCollection', features: [{ type: 'Feature' }, { type: 'Feature' }] },
          {
            headers: {
              'Content-Type': 'application/geo+json',
              'Content-Disposition':
                'attachment; filename="castor-westports-v4-2026-10-28.geojson"',
            },
          },
        ),
      ),
    )
    renderExchange()

    await userEvent.click(screen.getByRole('button', { name: 'Export GeoJSON' }))

    await waitFor(() => expect(saveTextFile).toHaveBeenCalledTimes(1))
    expect(vi.mocked(saveTextFile).mock.calls[0]![0]).toBe('castor-westports-v4-2026-10-28.geojson')
    expect(useEditorStore.getState().historyLog[0]?.label).toBe('Exported 2 features as GeoJSON')
  })

  it('says why when the export fails', async () => {
    server.use(
      http.get(`${BASE}/layouts/d-4/export`, () =>
        HttpResponse.json({ title: 'Not found', status: 404 }, { status: 404 }),
      ),
    )
    renderExchange()

    await userEvent.click(screen.getByRole('button', { name: 'Export GeoJSON' }))

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Not found'))
    expect(saveTextFile).not.toHaveBeenCalled()
  })
})

describe('GeoJSON import', () => {
  it('tries the file as a dry run first and shows the report card', async () => {
    serve()
    renderExchange()

    await choose()

    expect(await screen.findByText('Import of shapes.geojson')).toBeInTheDocument()
    expect(screen.getByText('1 added, 1 updated, 1 skipped')).toBeInTheDocument()
    expect(
      screen.getByText('Round trip: 12 coordinates, largest difference 0.457 mm'),
    ).toBeInTheDocument()
    expect(screen.getByText('Bow tie: The shape crosses itself')).toBeInTheDocument()
    expect(imports).toEqual(['dryRun=true'])
    expect(screen.getByRole('button', { name: 'Import' })).toBeEnabled()
  })

  it('shows 8 skip reasons and all of them on request', async () => {
    const reasons = Array.from({ length: 10 }, (_, i) => `Shape ${i + 1}: Overlaps Hall`)
    serve(report({ skipped: 10, skipReasons: reasons }))
    renderExchange()

    await choose()

    expect(await screen.findByText('Shape 8: Overlaps Hall')).toBeInTheDocument()
    expect(screen.queryByText('Shape 9: Overlaps Hall')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Show all 10' }))
    expect(screen.getByText('Shape 10: Overlaps Hall')).toBeInTheDocument()
  })

  it('stores nothing when the editor cancels', async () => {
    serve()
    renderExchange()
    await choose()

    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }))

    expect(screen.queryByText('Import of shapes.geojson')).not.toBeInTheDocument()
    expect(imports).toEqual(['dryRun=true'])
    expect(useEditorStore.getState().undoStack).toHaveLength(0)
  })

  it('imports for real, tells how many and records one undo step', async () => {
    serve()
    renderExchange()
    await choose()

    await userEvent.click(await screen.findByRole('button', { name: 'Import' }))

    await waitFor(() => expect(toast).toHaveBeenCalledWith('2 features imported, 1 skipped'))
    expect(imports).toEqual(['dryRun=true', 'dryRun=false'])
    expect(screen.queryByText('Import of shapes.geojson')).not.toBeInTheDocument()
    const editor = useEditorStore.getState()
    expect(editor.undoStack).toHaveLength(1)
    expect(editor.historyLog[0]?.label).toBe('Imported 2 features from shapes.geojson')
  })

  it('undoes the whole import: added assets go, updated ones get their values back', async () => {
    serve()
    renderExchange()
    await choose()
    await userEvent.click(await screen.findByRole('button', { name: 'Import' }))
    await waitFor(() => expect(useEditorStore.getState().undoStack).toHaveLength(1))

    runUndo()

    await waitFor(() => expect(requests).toHaveLength(2))
    expect(requests[0]).toMatchObject({ method: 'DELETE', url: 'a-2', ifMatch: '"0"' })
    expect(requests[1]).toMatchObject({ method: 'PATCH', url: 'a-1', ifMatch: '"2"' })
    expect(requests[1]!.body).toMatchObject({ name: 'Hall 1', lengthM: 80 })
    expect(requests[1]!.body).not.toHaveProperty('rowVersion')
    expect(requests[1]!.body).not.toHaveProperty('id')
  })

  it('has nothing to import when the file gives no shape', async () => {
    serve(report({ added: 0, updated: 0, skipped: 1 }))
    renderExchange()

    await choose()

    expect(await screen.findByRole('button', { name: 'Import' })).toBeDisabled()
  })

  it('shows what the server says when the file is refused', async () => {
    server.use(
      http.post(`${BASE}/layouts/d-4/import`, () =>
        HttpResponse.json(
          { title: 'Invalid request', status: 400, detail: 'That file is not valid JSON' },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    renderExchange()

    await choose('broken.geojson')

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('That file is not valid JSON'))
    expect(screen.queryByRole('region', { name: 'Import report' })).not.toBeInTheDocument()
  })
})
