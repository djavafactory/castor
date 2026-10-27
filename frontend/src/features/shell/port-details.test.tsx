import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { API, currentLayoutFixture, portFixture } from '@/test/fixtures'
import { server } from '@/test/setup'
import { PortDetails } from './port-details'

it('shows the empty text and the PORT section with the synthetic help', async () => {
  server.use(
    http.get(`${API}/v1/ports/WESTPORTS`, () => HttpResponse.json(portFixture)),
    http.get(`${API}/v1/ports/WESTPORTS/layouts/current`, () =>
      HttpResponse.json(currentLayoutFixture, { headers: { ETag: '"a"' } }),
    ),
  )
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <PortDetails />
    </QueryClientProvider>,
  )

  expect(
    screen.getByText('Select something in the 3D view to see its details.'),
  ).toBeInTheDocument()
  expect(await screen.findByText('v3, published 27 Oct 2026 14:05 by pia')).toBeInTheDocument()
  expect(screen.getByText('WGS84 lon/lat; metres in EPSG:3380')).toBeInTheDocument()
  expect(screen.getByText('Assets')).toBeInTheDocument()
  expect(
    screen.getByText(
      'Berths, yards and equipment are synthetic placeholders, not a survey of Westports.',
    ),
  ).toBeInTheDocument()
})
