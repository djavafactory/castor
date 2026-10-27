import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { API, assetTypesFixture, catalogueFixture } from '@/test/fixtures'
import { server } from '@/test/setup'
import { CatalogueTab } from './catalogue-tab'

it('renders the cargo types, ship classes and asset types from the API', async () => {
  server.use(
    http.get(`${API}/v1/catalogue`, () => HttpResponse.json(catalogueFixture)),
    http.get(`${API}/v1/asset-types`, () => HttpResponse.json(assetTypesFixture)),
  )
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <CatalogueTab />
    </QueryClientProvider>,
  )

  const cargo = await screen.findByRole('table', { name: 'Cargo types' })
  expect(within(cargo).getByText('Thermal coal')).toBeInTheDocument()
  expect(within(cargo).getByText('Dry bulk')).toBeInTheDocument()
  expect(within(cargo).getByText('0.85')).toBeInTheDocument()
  expect(screen.getByText('Densities are typical values, not a standard.')).toBeInTheDocument()

  const ships = screen.getByRole('table', { name: 'Ship classes' })
  expect(within(ships).getByText('Supramax')).toBeInTheDocument()
  expect(within(ships).getByText('58,000')).toBeInTheDocument()

  const types = await screen.findByRole('table', { name: 'Asset types' })
  expect(within(types).getByText('Excavator')).toBeInTheDocument()
  expect(within(types).getByText('Material handling')).toBeInTheDocument()
  expect(within(types).getByText('10 x 3.2 x 3.4')).toBeInTheDocument()
})
