import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { useMapStore } from '@/components/map/map-store'
import { queryKeys } from '@/lib/api'
import { setActiveFrame } from '@/lib/geo'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { layoutWire } from '@/test/fixtures'
import { server } from '@/test/setup'
import { IssuesTab } from './issues-tab'

const BASE = 'http://api.test/api/v1'
const DRAFT = `${BASE}/ports/WESTPORTS/layouts/draft`
const VALIDATE = `${BASE}/layouts/v-1/validate`

const SQUARE = [
  [
    [101.3066, 2.9534],
    [101.3068, 2.9534],
    [101.3068, 2.9536],
    [101.3066, 2.9536],
    [101.3066, 2.9534],
  ],
]

/** A draft whose assets have names and shapes, as GET /layouts/draft sends them. */
function draftWire(
  names: string[] = ['Warehouse A', 'Warehouse B'],
  rowVersions: number[] = [1, 1],
) {
  const wire = layoutWire({
    versionId: 'v-1',
    number: 2,
    status: 'DRAFT',
    assets: names.map((name, i) => ({
      id: ['wh-a', 'wh-b', 'wh-c', 'wh-d', 'wh-e'][i]!,
      typeCode: 'WAREHOUSE',
      rowVersion: rowVersions[i] ?? 1,
      name,
    })),
  })
  for (const f of wire.features.features) {
    ;(f as { geometry: unknown }).geometry = { type: 'Polygon', coordinates: SQUARE }
  }
  return wire
}

const OVERLAP = {
  code: 'OVERLAP',
  level: 'ERROR',
  message: 'Overlaps Warehouse B',
  assetIds: ['wh-a', 'wh-b'],
}
const NO_SURVEY = {
  code: 'APPROXIMATE_SOURCE',
  level: 'WARNING',
  message: '2 shapes have no survey',
  assetIds: ['wh-a', 'wh-b'],
}

let validations = 0

function answer(issues: unknown[], rowVersions: number[] = [1, 1]) {
  const errors = (issues as { level: string }[]).filter((i) => i.level === 'ERROR').length
  server.use(
    http.get(DRAFT, () => HttpResponse.json(draftWire(undefined, rowVersions))),
    http.post(VALIDATE, () => {
      validations += 1
      return HttpResponse.json({ errors, warnings: issues.length - errors, issues })
    }),
  )
}

function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <IssuesTab />
      </QueryClientProvider>,
    ),
  }
}

beforeEach(() => {
  validations = 0
  useEditorStore.getState().reset()
  useSelectionStore.getState().clear()
  setActiveFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 })
})

describe('Issues tab', () => {
  it('counts errors and warnings and lists each issue with the names of its assets', async () => {
    answer([OVERLAP, NO_SURVEY])
    renderTab()

    expect(await screen.findByText('1 error (blocks publishing), 1 warning')).toBeInTheDocument()
    const rows = within(screen.getByRole('list', { name: 'Issues found' })).getAllByRole('listitem')
    expect(rows).toHaveLength(2)
    // an overlap names both assets: the message gives the other one, the second line both
    expect(within(rows[0]!).getByText('Overlaps Warehouse B')).toBeInTheDocument()
    expect(within(rows[0]!).getByText('Warehouse A · Warehouse B')).toBeInTheDocument()
    expect(within(rows[0]!).getByRole('img', { name: 'Error' })).toBeInTheDocument()
    expect(within(rows[1]!).getByRole('img', { name: 'Warning' })).toBeInTheDocument()
  })

  it('writes the plural of the count', async () => {
    answer([
      OVERLAP,
      {
        code: 'ON_WATER',
        level: 'ERROR',
        message: 'Extends past the quay edge into the water',
        assetIds: ['wh-a'],
      },
    ])
    renderTab()

    expect(await screen.findByText('2 errors (block publishing), 0 warnings')).toBeInTheDocument()
  })

  it('says the layout can be published when there is nothing to fix', async () => {
    answer([])
    renderTab()

    expect(await screen.findByText('No issues. The layout can be published.')).toBeInTheDocument()
    expect(screen.getByText('0 errors (block publishing), 0 warnings')).toBeInTheDocument()
  })

  it('selects the first asset of a row and zooms the map to it', async () => {
    answer([OVERLAP])
    renderTab()
    const original = useMapStore.getState().fit
    const boxes: { x0: number; x1: number; y0: number; y1: number }[] = []
    useMapStore.setState({ fit: (box) => boxes.push(box) })

    try {
      await userEvent.click(await screen.findByRole('button', { name: /Overlaps Warehouse B/ }))
    } finally {
      useMapStore.setState({ fit: original })
    }

    expect(useSelectionStore.getState().selectedAssetId).toBe('wh-a')
    expect(boxes).toHaveLength(1)
    expect(boxes[0]!.x1).toBeGreaterThan(boxes[0]!.x0)
    expect(boxes[0]!.y1).toBeGreaterThan(boxes[0]!.y0)
  })

  it('does not repeat the name of a shape the message already names', async () => {
    answer([
      {
        code: 'GEOM_INVALID',
        level: 'ERROR',
        message: 'Warehouse A has an invalid shape',
        assetIds: ['wh-a'],
      },
    ])
    renderTab()

    await screen.findByText('Warehouse A has an invalid shape')
    expect(screen.getAllByText(/Warehouse A/)).toHaveLength(1)
  })

  it('shortens a row that is about many shapes', async () => {
    const ids = ['wh-a', 'wh-b', 'wh-c', 'wh-d', 'wh-e']
    server.use(
      http.get(DRAFT, () =>
        HttpResponse.json(draftWire(['Hall 1', 'Hall 2', 'Hall 3', 'Hall 4', 'Hall 5'])),
      ),
      http.post(VALIDATE, () =>
        HttpResponse.json({
          errors: 0,
          warnings: 1,
          issues: [{ ...NO_SURVEY, message: '5 shapes have no survey', assetIds: ids }],
        }),
      ),
    )
    renderTab()

    expect(await screen.findByText('Hall 1 · Hall 2 · Hall 3 and 2 more')).toBeInTheDocument()
  })

  it('checks again on demand', async () => {
    answer([OVERLAP])
    renderTab()
    await screen.findByText('Overlaps Warehouse B')
    expect(validations).toBe(1)

    await userEvent.click(screen.getByRole('button', { name: 'Check again' }))

    await waitFor(() => expect(validations).toBe(2))
  })

  it('shows an overlap made by moving a warehouse as soon as the save is read back', async () => {
    answer([])
    const { client } = renderTab()
    await screen.findByText('No issues. The layout can be published.')

    // Warehouse A is moved onto Warehouse B: the server now has a newer row version and an overlap
    answer([OVERLAP], [2, 1])
    await act(() => client.invalidateQueries({ queryKey: queryKeys.draft('WESTPORTS') }))

    expect(await screen.findByText('Overlaps Warehouse B')).toBeInTheDocument()
    expect(screen.getByText('Warehouse A · Warehouse B')).toBeInTheDocument()
    expect(screen.getByText('1 error (blocks publishing), 0 warnings')).toBeInTheDocument()
  })

  it('waits while a save is in flight and checks the saved draft afterwards', async () => {
    answer([])
    useEditorStore.getState().beginSave('wh-a')
    renderTab()

    await new Promise((resolve) => setTimeout(resolve, 80))
    expect(validations).toBe(0)

    act(() => useEditorStore.getState().endSave('wh-a'))
    await waitFor(() => expect(validations).toBe(1))
  })

  it('shows the error card with Retry when the check fails', async () => {
    server.use(
      http.get(DRAFT, () => HttpResponse.json(draftWire())),
      http.post(VALIDATE, () =>
        HttpResponse.json(
          { title: 'Something broke', status: 500 },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    renderTab()

    expect(await screen.findByRole('alert')).toHaveTextContent('Something broke')
    answer([])
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('No issues. The layout can be published.')).toBeInTheDocument()
  })
})
