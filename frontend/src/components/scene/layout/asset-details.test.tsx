import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { API, assetTypesFixture, catalogueFixture, currentLayoutFixture } from '@/test/fixtures'
import { server } from '@/test/setup'
import { assetHoverText } from './asset-text'
import {
  capacityOf,
  coord,
  detailRows,
  headingText,
  hoverLines,
  humanKey,
  sizeText,
} from './asset-rows'
import { LayoutAssetDetails } from './asset-details'

function renderDetails() {
  server.use(
    http.get(`${API}/v1/asset-types`, () => HttpResponse.json(assetTypesFixture)),
    http.get(`${API}/v1/catalogue`, () => HttpResponse.json(catalogueFixture)),
    http.get(`${API}/v1/ports/WESTPORTS/layouts/current`, () =>
      HttpResponse.json(currentLayoutFixture, { headers: { ETag: '"a"' } }),
    ),
  )
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <LayoutAssetDetails />
    </QueryClientProvider>,
  )
}

beforeEach(() => useSelectionStore.getState().clear())

describe('LayoutAssetDetails', () => {
  it('shows nothing until an asset is selected', () => {
    const { container } = renderDetails()
    expect(container).toBeEmptyDOMElement()
  })

  it('reads the selected asset in sections, with the only control being Deselect', async () => {
    useSelectionStore.getState().select('wh1')
    renderDetails()
    expect(await screen.findByText('Warehouse 1')).toBeInTheDocument()
    for (const heading of ['Identity', 'Position', 'Details', 'Data']) {
      expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument()
    }
    expect(screen.getByText('45.0 deg true')).toBeInTheDocument()
    expect(screen.getByText('1,800 m2 footprint')).toBeInTheDocument()
    expect(screen.getByText('Warehouse: Dock Doors')).toBeInTheDocument()
    expect(screen.getByText('Approximate')).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.queryByRole('spinbutton')).toBeNull()
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual(['Deselect'])
  })

  it('shows coordinates to 7 decimals and the design fill bar of a yard', async () => {
    useSelectionStore.getState().select('cy1')
    renderDetails()
    expect(await screen.findByText('Yard 1')).toBeInTheDocument()
    expect(screen.getByText('900 TEU')).toBeInTheDocument()
    expect(screen.getByText('50% (design fill, static)')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Design fill' })).toHaveAttribute(
      'aria-valuenow',
      '50',
    )
    const longitude = screen.getByText('Longitude').nextElementSibling
    expect(longitude?.textContent).toMatch(/^\d+\.\d{7}$/)
  })

  it('deselects', async () => {
    useSelectionStore.getState().select('wh1')
    renderDetails()
    await userEvent.click(await screen.findByRole('button', { name: 'Deselect' }))
    expect(useSelectionStore.getState().selectedAssetId).toBeNull()
  })
})

describe('detail helpers', () => {
  it('turns member names into words', () => {
    expect(humanKey('maxStackM')).toBe('Max Stack M')
    expect(humanKey('berth')).toBe('Berth')
  })

  it('lists the filled members of each group and skips the empty ones', () => {
    expect(
      detailRows({
        berth: { depthM: 14, cargoFamilies: ['BULK', 'GENERAL'], note: null },
        zone: { allowedTypes: [] },
        storage: { weatherExposed: true },
      }),
    ).toEqual([
      ['Berth: Depth M', '14'],
      ['Berth: Cargo Families', 'BULK, GENERAL'],
      ['Storage: Weather Exposed', 'Yes'],
    ])
  })

  it('writes the hover text with the code only when it adds something', () => {
    expect(assetHoverText({ name: 'Berth 4', code: 'B4' })).toBe('Berth 4 (B4)')
    expect(assetHoverText({ name: 'B4', code: 'B4' })).toBe('B4')
    expect(assetHoverText({ name: '', code: 'B4' })).toBe('B4')
  })
})

describe('detail text', () => {
  const props = (over: Record<string, unknown>) =>
    ({
      code: 'B4',
      type: 'BERTH',
      name: 'Berth 4',
      anchor: [101.3, 2.9],
      headingDeg: 90,
      lengthM: 250,
      details: { berth: { depthM: 15 } },
      provenance: { source: 'SYNTHETIC', accuracy: 'APPROXIMATE' },
      computed: {},
      rowVersion: 1,
      ...over,
    }) as never

  it('writes coordinates to 7 decimals', () => {
    expect(coord(101.3)).toBe('101.3000000')
  })

  it('marks the heading of a berth as the quay line', () => {
    expect(headingText(props({}))).toBe('90.0 deg true (quay line)')
    expect(headingText(props({ type: 'WAREHOUSE' }))).toBe('90.0 deg true')
    expect(headingText(props({ headingDeg: null }))).toBeNull()
  })

  it('sizes berths, polygons and boxes', () => {
    expect(sizeText(props({}))).toBe('250 m quay, 15 m deep')
    expect(sizeText(props({ type: 'PAD', computed: { areaM2: 12345.6 } }), 'Polygon')).toBe(
      '12,346 m2 footprint',
    )
    expect(sizeText(props({ type: 'PAD', lengthM: 60, widthM: 30, heightM: 12 }))).toBe(
      '60 x 30 m, 12 m high',
    )
  })

  it('reads capacity and design fill', () => {
    expect(
      capacityOf(
        props({
          details: { storage: { capacityValue: 900, capacityUnit: 't', designFill: 0.95 } },
        }),
      ),
    ).toEqual({ text: '900 t', fillPct: 95 })
    expect(capacityOf(props({ details: {} }))).toBeNull()
  })

  it('lists heading in the tooltip only for mobile equipment', () => {
    const mobile = hoverLines(
      props({ type: 'FORKLIFT', details: { mobile: { positionMode: 'x' } } }),
      'Forklift',
    )
    expect(mobile).toContain('Heading 90.0 deg true')
    expect(mobile).toContain('2.9000000, 101.3000000')
    expect(hoverLines(props({}), 'Berth').some((l) => l.startsWith('Heading'))).toBe(false)
  })
})
