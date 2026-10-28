import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Role } from '@/lib/stores/auth-store'
import { useAuthStore } from '@/lib/stores/auth-store'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { layoutWire, portFixture } from '@/test/fixtures'
import { server } from '@/test/setup'
import { VersionsTab } from './versions-tab'

vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { error: vi.fn(), dismiss: vi.fn(), loading: vi.fn() }),
}))

const BASE = 'http://api.test/api/v1'
const PORT = `${BASE}/ports/WESTPORTS`

const VERSIONS = [
  {
    id: 'd-4',
    versionNo: 4,
    status: 'DRAFT',
    createdBy: 'ada',
    createdAt: '2026-10-28T01:00:00Z',
    assetCount: 2,
  },
  {
    id: 'v-3',
    versionNo: 3,
    status: 'PUBLISHED',
    createdBy: 'ada',
    createdAt: '2026-10-27T01:00:00Z',
    publishedBy: 'pat',
    publishedAt: '2026-10-27T06:05:00Z',
    reviewedBy: 'rita',
    restoredFromVersionNo: 1,
    note: 'Back to v1',
    assetCount: 301,
  },
  {
    id: 'v-1',
    versionNo: 1,
    status: 'SUPERSEDED',
    createdBy: 'seed',
    createdAt: '2026-10-01T01:00:00Z',
    publishedBy: 'seed',
    publishedAt: '2026-10-01T01:00:00Z',
    assetCount: 1,
  },
]

const DIFF = {
  added: [{ assetId: 'a-3', code: 'WH-3', name: 'Hall 3', type: 'WAREHOUSE' }],
  removed: [{ assetId: 'a-2', code: 'WH-2', name: 'Hall 2', type: 'WAREHOUSE' }],
  changed: [{ assetId: 'a-1', code: 'WH-1', name: 'Hall 1', fields: ['geometry', 'heightM'] }],
}

const draftWire = () =>
  layoutWire(
    {
      versionId: 'd-4',
      number: 4,
      status: 'DRAFT',
      assets: [{ id: 'a-1', typeCode: 'WAREHOUSE', rowVersion: 1, name: 'Hall 1' }],
    },
    { basedOnVersionNo: 3, updatedBy: 'pat', updatedAt: '2026-10-28T02:35:00Z' },
  )

let calls: string[] = []
let bodies: Record<string, unknown> = {}

function serve(options: { errors?: number; versions?: unknown[] } = {}) {
  const record = async (key: string, request: Request) => {
    calls.push(key)
    bodies[key] = await request.json().catch(() => null)
  }
  server.use(
    http.get(`${BASE}/ports/WESTPORTS`, () => HttpResponse.json(portFixture)),
    http.get(`${PORT}/layouts/draft`, () => HttpResponse.json(draftWire())),
    http.get(`${PORT}/layouts`, () => HttpResponse.json(options.versions ?? VERSIONS)),
    http.get(`${BASE}/layouts/d-4/diff`, () => HttpResponse.json(DIFF)),
    http.get(`${BASE}/reviewers`, () =>
      HttpResponse.json([
        { username: 'rita', displayName: 'Rita' },
        { username: 'sam', displayName: 'Sam Lee' },
      ]),
    ),
    http.post(`${BASE}/layouts/d-4/validate`, () => {
      const errors = options.errors ?? 0
      return HttpResponse.json({
        errors,
        warnings: 0,
        issues: Array.from({ length: errors }, () => ({
          code: 'OVERLAP',
          level: 'ERROR',
          message: 'Overlaps Hall 2',
          assetIds: ['a-1'],
        })),
      })
    }),
    http.post(`${BASE}/layouts/d-4/publish`, async ({ request }) => {
      await record('publish', request)
      return HttpResponse.json({
        versionNo: 4,
        publishedAt: '2026-10-28T03:00:00Z',
        publishedBy: 'pat',
        reviewedBy: 'rita',
        assetCount: 1,
      })
    }),
    http.post(`${BASE}/layouts/v-1/restore`, async ({ request }) => {
      await record('restore', request)
      return HttpResponse.json(draftWire(), { status: 201 })
    }),
    http.delete(`${BASE}/layouts/d-4`, () => {
      calls.push('discard')
      return new HttpResponse(null, { status: 204 })
    }),
    http.post(PORT + '/layouts', async ({ request }) => {
      await record('create', request)
      return HttpResponse.json(draftWire(), { status: 201 })
    }),
  )
}

function renderTab(roles: Role[] = ['PUBLISHER']) {
  useAuthStore.setState({ username: 'pat', displayName: 'Pat', roles, credential: 'Basic x' })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <VersionsTab />
    </QueryClientProvider>,
  )
  return client
}

beforeEach(() => {
  calls = []
  bodies = {}
  vi.mocked(toast).mockClear()
  vi.mocked(toast.error).mockClear()
  useEditorStore.getState().reset()
  useUiStore.setState({ mode: 'design', rightTab: 'versions' })
})

describe('Versions tab: this layout', () => {
  it('shows the status, the published version, the draft and the changes against it', async () => {
    serve()
    renderTab()

    expect(await screen.findByText('Draft (based on v3)')).toBeInTheDocument()
    expect(screen.getByText('version 3')).toBeInTheDocument()
    expect(screen.getByText('based on v3, last change 28 Oct, 10:35 by pat')).toBeInTheDocument()
    expect(await screen.findByText('1 added, 1 changed, 1 removed')).toBeInTheDocument()
    expect(screen.getByText('+ WH-3 Hall 3')).toBeInTheDocument()
    expect(screen.getByText('~ WH-1 Hall 1 (geometry, heightM)')).toBeInTheDocument()
    expect(screen.getByText('- WH-2 Hall 2')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('What changed')).toBeInTheDocument()
  })

  it('says "none yet" when nothing is published', async () => {
    serve({ versions: [VERSIONS[0]] })
    renderTab()

    expect(await screen.findByText('none yet')).toBeInTheDocument()
    expect(await screen.findByText('Nothing is published yet.')).toBeInTheDocument()
  })
})

describe('Versions tab: published versions', () => {
  it('lists the versions newest first with live, who, restored from, reviewer and note', async () => {
    serve()
    renderTab()

    const table = await screen.findByRole('table', { name: 'Published versions' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(within(rows[0]!).getByText('v3')).toBeInTheDocument()
    expect(within(rows[0]!).getByText('live')).toBeInTheDocument()
    expect(within(rows[0]!).getByText('restored from v1')).toBeInTheDocument()
    expect(within(rows[0]!).getByText('27 Oct 2026 14:05, pat, 301 assets')).toBeInTheDocument()
    expect(within(rows[0]!).getByText('reviewed by rita')).toBeInTheDocument()
    expect(within(rows[0]!).getByText('Back to v1')).toBeInTheDocument()
    expect(within(rows[1]!).getByText('v1')).toBeInTheDocument()
    expect(within(rows[1]!).getByText('01 Oct 2026 09:00, seed, 1 asset')).toBeInTheDocument()
    expect(within(rows[1]!).getByText('-')).toBeInTheDocument()
  })

  it('restores a version after a confirmation and says the asset ids are unchanged', async () => {
    serve()
    renderTab()

    await userEvent.click(await screen.findByRole('button', { name: 'Restore version 1' }))
    expect(
      screen.getByText('Load version 1 into a new draft? Asset IDs stay the same.'),
    ).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Restore' }))

    await waitFor(() =>
      expect(toast).toHaveBeenCalledWith(
        'Version 1 loaded. Asset IDs are unchanged. Publish to make it live.',
      ),
    )
    expect(bodies.restore).toEqual({ discardDraft: true })
  })
})

describe('Versions tab: publish', () => {
  it('is refused with a toast and the Issues tab when the draft has errors', async () => {
    serve({ errors: 2 })
    renderTab()

    await userEvent.click(await screen.findByRole('button', { name: 'Publish' }))

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('Publishing blocked: 2 error(s). Fix them first.'),
    )
    expect(useUiStore.getState().rightTab).toBe('issues')
    expect(calls).not.toContain('publish')
  })

  it('confirms with the changes, the note and an optional reviewer, then publishes', async () => {
    serve()
    renderTab()
    await screen.findByText('1 added, 1 changed, 1 removed')
    await userEvent.type(screen.getByPlaceholderText('What changed'), 'Moved gate')

    await userEvent.click(screen.getByRole('button', { name: 'Publish' }))

    const dialog = await screen.findByRole('alertdialog')
    expect(within(dialog).getByText('1 added, 1 changed, 1 removed')).toBeInTheDocument()
    expect(within(dialog).getByText('Moved gate')).toBeInTheDocument()
    await userEvent.selectOptions(within(dialog).getByLabelText('Reviewed by (optional)'), 'rita')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))

    await waitFor(() =>
      expect(toast).toHaveBeenCalledWith('Version 4 published. Monitor shows it now.'),
    )
    expect(bodies.publish).toEqual({ note: 'Moved gate', reviewedBy: 'rita' })
    // the port has no draft after a publish; a new one is started from the published layout
    await waitFor(() => expect(bodies.create).toEqual({ basedOn: 'published' }))
  })

  it('publishes without a reviewer when none is chosen', async () => {
    serve()
    renderTab()
    await screen.findByText('1 added, 1 changed, 1 removed')

    await userEvent.click(screen.getByRole('button', { name: 'Publish' }))
    const dialog = await screen.findByRole('alertdialog')
    expect(within(dialog).getByText('-')).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))

    await waitFor(() => expect(bodies.publish).toEqual({ note: '' }))
  })

  it('is disabled for an editor, with the reason as its tooltip, and hides the publisher buttons', async () => {
    serve()
    renderTab(['EDITOR'])

    const publish = await screen.findByRole('button', { name: 'Publish' })
    expect(publish).toBeDisabled()
    expect(publish.closest('span')).toHaveAttribute('title', 'Only publishers can publish')
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Check and save' })).toBeEnabled(),
    )
    expect(screen.queryByRole('button', { name: 'Discard draft' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Restore version/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New blank layout' })).not.toBeInTheDocument()
  })
})

describe('Versions tab: discard and blank layout', () => {
  it('discards the draft after the confirmation', async () => {
    serve()
    renderTab()

    await userEvent.click(await screen.findByRole('button', { name: 'Discard draft' }))
    expect(
      screen.getByText('Discard the draft? All unpublished changes are lost.'),
    ).toBeInTheDocument()
    await userEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Discard draft' }),
    )

    await waitFor(() => expect(calls).toContain('discard'))
  })

  it('starts a blank draft: the current draft is discarded, a blank one is created and the toast says so', async () => {
    serve()
    renderTab()

    await userEvent.click(await screen.findByRole('button', { name: 'New blank layout' }))
    expect(
      screen.getByText('Start a blank draft? The current draft is discarded.'),
    ).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Start blank' }))

    await waitFor(() =>
      expect(toast).toHaveBeenCalledWith(
        'Blank layout on the satellite basemap. Search, place and draw, then publish.',
      ),
    )
    expect(calls.indexOf('discard')).toBeLessThan(calls.indexOf('create'))
    expect(bodies.create).toEqual({ basedOn: 'blank' })
  })
})
