import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/setup'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { BuildingDetails } from './building-details'

const BASE = 'http://api.test/api'

const FEATURES = {
  type: 'FeatureCollection',
  features: [
    {
      type: 'Feature',
      id: 'way/1300405460',
      geometry: {
        type: 'Polygon',
        coordinates: [
          [
            [101.3, 2.9],
            [101.3, 2.91],
            [101.31, 2.91],
            [101.3, 2.9],
          ],
        ],
      },
      properties: {
        kind: 'BUILDING',
        osmRef: 'way/1300405460',
        name: null,
        heightM: 12,
        areaM2: 1812.4,
        source: 'OSM',
        accuracy: 'APPROXIMATE',
      },
    },
  ],
}

function renderDetails() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <BuildingDetails />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  useSelectionStore.getState().clear()
  server.use(
    http.get(`${BASE}/v1/ports/WESTPORTS/reference-features`, () => HttpResponse.json(FEATURES)),
  )
})

describe('BuildingDetails', () => {
  it('shows nothing until a building is selected', () => {
    const { container } = renderDetails()
    expect(container).toBeEmptyDOMElement()
  })

  it('shows the selected building, read-only', async () => {
    useSelectionStore.getState().selectReference('way/1300405460')
    renderDetails()

    expect(await screen.findByRole('heading', { name: 'Building 1300405460' })).toBeInTheDocument()
    expect(screen.getByText('Reference feature, read-only')).toBeInTheDocument()
    expect(screen.getByText('way/1300405460')).toBeInTheDocument()
    expect(screen.getByText('1812 m2')).toBeInTheDocument()
    expect(screen.getByText('12 m')).toBeInTheDocument()
    expect(screen.getByText('OpenStreetMap (ODbL)')).toBeInTheDocument()
    expect(screen.getByText('Approximate')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })
})
