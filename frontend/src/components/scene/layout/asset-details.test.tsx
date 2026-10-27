import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { API, currentLayoutFixture } from '@/test/fixtures'
import { server } from '@/test/setup'
import { assetHoverText } from './asset-text'
import { detailRows, humanKey } from './asset-rows'
import { LayoutAssetDetails } from './asset-details'

function renderDetails() {
  server.use(
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

  it('reads the selected asset, read-only', async () => {
    useSelectionStore.getState().select('wh1')
    renderDetails()
    expect(await screen.findByRole('heading', { name: 'Warehouse 1' })).toBeInTheDocument()
    expect(screen.getByText(/published layout, read-only/)).toBeInTheDocument()
    expect(screen.getByText('60 m')).toBeInTheDocument()
    expect(screen.getByText('45°')).toBeInTheDocument()
    expect(screen.getByText('1800 m2')).toBeInTheDocument()
    expect(screen.getByText('Warehouse: Dock Doors')).toBeInTheDocument()
    expect(screen.getByText('Approximate')).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.queryByRole('spinbutton')).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
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
