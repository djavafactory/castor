import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import {
  API,
  catalogueFixture,
  layoutWire,
  portFixture,
  warehouseTypeFixture,
} from '@/test/fixtures'
import { server } from '@/test/setup'
import { DesignInspector } from './inspector'

vi.mock('sonner', () => ({ toast: vi.fn() }))

const BASE = `${API}/v1`
const DRAFT = `${BASE}/ports/WESTPORTS/layouts/draft`
const VALIDATE = `${BASE}/layouts/v-1/validate`
const ASSETS = `${BASE}/layouts/v-1/assets`

const SQUARE = [
  [
    [101.3064, 2.9532],
    [101.3068, 2.9532],
    [101.3068, 2.9536],
    [101.3064, 2.9536],
    [101.3064, 2.9532],
  ],
]

/** A draft with one warehouse, as GET /layouts/draft sends it: the feature properties of the inspector. */
function draftWire() {
  const wire = layoutWire({
    versionId: 'v-1',
    number: 2,
    status: 'DRAFT',
    assets: [
      {
        id: 'wh-1',
        typeCode: 'WAREHOUSE',
        rowVersion: 3,
        name: 'Warehouse 1',
        code: 'WH-1',
        anchor: [101.3066, 2.9534],
        lengthM: 80,
        widthM: 40,
        heightM: 12,
        headingDeg: 45,
        clearanceM: 0,
        elevationM: 0,
        modelRev: 1,
        storageZoneCode: null,
        operatorName: null,
        description: null,
        links: [],
        override: null,
        details: { warehouse: { roofRiseM: 3, dockDoors: 6, doorSide: 'NORTH' } },
        provenance: { source: 'OPERATOR', accuracy: 'APPROXIMATE', capturedOn: null, note: null },
        computed: { areaM2: 3200, local: { x: 0, y: 0 } },
      },
    ],
  })
  for (const f of wire.features.features)
    (f as { geometry: unknown }).geometry = { type: 'Polygon', coordinates: SQUARE }
  return wire
}

const EMPTY_REPORT = { errors: 0, warnings: 0, issues: [] }

function renderInspector() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <DesignInspector />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  useSelectionStore.getState().clear()
  useEditorStore.getState().reset()
  useUiStore.setState({ lockedLayers: {} })
  vi.mocked(toast).mockClear()
  server.use(
    http.get(`${BASE}/ports/WESTPORTS`, () => HttpResponse.json(portFixture)),
    http.get(`${BASE}/asset-types`, () => HttpResponse.json([warehouseTypeFixture])),
    http.get(`${BASE}/catalogue`, () => HttpResponse.json(catalogueFixture)),
    http.get(DRAFT, () => HttpResponse.json(draftWire())),
    http.post(VALIDATE, () => HttpResponse.json(EMPTY_REPORT)),
  )
})

describe('DesignInspector', () => {
  it('shows the layout summary and how to while nothing is selected', async () => {
    renderInspector()
    expect(
      await screen.findByText(
        'Select a shape on the map or in 3D, or pick a template from the palette.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Layout' })).toBeInTheDocument()
    expect(screen.getByText('WGS84 lon/lat (EPSG:4326)')).toBeInTheDocument()
    expect(screen.getByText('metres (EPSG:3380)')).toBeInTheDocument()
    expect(await screen.findByText('0 errors, 0 warnings')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Check and save' })).toBeInTheDocument()
  })

  it('shows the header of the selected asset, NO SURVEY for an approximate shape, and every section', async () => {
    useSelectionStore.getState().select('wh-1')
    renderInspector()
    expect(await screen.findByRole('heading', { name: 'Warehouse 1' })).toBeInTheDocument()
    expect(screen.getByText('NO SURVEY')).toBeInTheDocument()
    for (const section of [
      'Asset properties',
      'Position',
      'Dimensions',
      'Operational semantics',
      'Provenance and accuracy',
      'Validation',
      'Actions',
    ]) {
      expect(screen.getByRole('region', { name: section })).toBeInTheDocument()
    }
    expect(screen.getByRole('spinbutton', { name: 'Length' })).toHaveValue(80)
    expect(screen.getByRole('spinbutton', { name: 'Roof rise' })).toHaveValue(3)
    expect(screen.getByRole('combobox', { name: 'Door side' })).toHaveValue('NORTH')
    expect(screen.getByText('WAREHOUSE rev 1 (procedural low-poly)')).toBeInTheDocument()
  })

  it('saves a changed name on Enter as one PATCH with the row version and one undo step', async () => {
    const patches: { body: unknown; ifMatch: string | null }[] = []
    server.use(
      http.patch(`${ASSETS}/:id`, async ({ request }) => {
        patches.push({ body: await request.json(), ifMatch: request.headers.get('If-Match') })
        return HttpResponse.json(
          {
            feature: {
              type: 'Feature',
              id: 'wh-1',
              geometry: { type: 'Polygon', coordinates: SQUARE },
              properties: { type: 'WAREHOUSE', rowVersion: 4 },
            },
            warnings: [],
            adjustmentMm: 0.4,
          },
          { headers: { ETag: '"4"' } },
        )
      }),
    )
    useSelectionStore.getState().select('wh-1')
    renderInspector()
    const name = await screen.findByRole('textbox', { name: 'Name' })
    await userEvent.clear(name)
    await userEvent.type(name, 'Shed 1{Enter}')
    await waitFor(() => expect(patches).toHaveLength(1))
    expect(patches[0]).toEqual({ body: { name: 'Shed 1' }, ifMatch: '"3"' })
    expect(useEditorStore.getState().undoStack.at(-1)?.label).toBe('Name of Warehouse 1')
    expect(useEditorStore.getState().largestAdjustmentMm).toBe(0.4)
  })

  it('refuses an invalid whole number with a red outline and says why in a toast, without saving', async () => {
    const patches: unknown[] = []
    server.use(
      http.patch(`${ASSETS}/:id`, async ({ request }) => {
        patches.push(await request.json())
        return HttpResponse.json({}, { status: 500 })
      }),
    )
    useSelectionStore.getState().select('wh-1')
    renderInspector()
    const docks = await screen.findByRole('spinbutton', { name: 'Dock doors' })
    await userEvent.clear(docks)
    await userEvent.type(docks, '2.5{Enter}')
    expect(docks).toHaveAttribute('aria-invalid', 'true')
    expect(toast).toHaveBeenCalledWith('Dock doors: enter a whole number of at least 0')
    expect(patches).toHaveLength(0)
  })

  it('shows LOCKED and disables the fields and the actions of a locked layer', async () => {
    useUiStore.setState({ lockedLayers: { buildings: true } })
    useSelectionStore.getState().select('wh-1')
    renderInspector()
    expect(await screen.findByText('LOCKED')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Name' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Duplicate' })).toBeDisabled()
  })

  it('Check and save reports that every change is saved with the largest adjustment', async () => {
    renderInspector()
    await userEvent.click(await screen.findByRole('button', { name: 'Check and save' }))
    expect(
      await screen.findByText(
        'All changes saved. Coordinates stored as WGS84 to 7 decimals (largest adjustment 0.0 mm).',
      ),
    ).toBeInTheDocument()
  })
})
