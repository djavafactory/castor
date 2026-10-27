import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { useCatalogue } from '@/lib/api'
import { API, catalogueFixture } from '@/test/fixtures'
import { wrapperFor } from '@/test/query-wrapper'
import { server } from '@/test/setup'
import { QueryBoundary } from './query-boundary'

function Catalogue() {
  const query = useCatalogue()
  return (
    <QueryBoundary query={query}>{(data) => <p>Density note: {data.densityNote}</p>}</QueryBoundary>
  )
}

function renderCatalogue() {
  const Wrapper = wrapperFor()
  return render(
    <Wrapper>
      <Catalogue />
    </Wrapper>,
  )
}

describe('QueryBoundary', () => {
  it('shows a skeleton, then the data', async () => {
    server.use(http.get(`${API}/v1/catalogue`, () => HttpResponse.json(catalogueFixture)))
    renderCatalogue()

    expect(screen.getByTestId('skeleton')).toBeInTheDocument()
    expect(await screen.findByText(/Densities are typical values/)).toBeInTheDocument()
  })

  it('shows the error card with Retry when the response has the wrong shape', async () => {
    let wrong = true
    server.use(
      http.get(`${API}/v1/catalogue`, () =>
        wrong ? HttpResponse.json({ cargoTypes: 'many' }) : HttpResponse.json(catalogueFixture),
      ),
    )
    renderCatalogue()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Unexpected response from the server')

    wrong = false
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    await waitFor(() =>
      expect(screen.getByText(/Densities are typical values/)).toBeInTheDocument(),
    )
  })

  it('shows the Problem Details title and detail of a failed request', async () => {
    server.use(
      http.get(`${API}/v1/catalogue`, () =>
        HttpResponse.json(
          { title: 'Forbidden', status: 403, detail: 'You are not allowed to do this' },
          { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    renderCatalogue()

    expect(await screen.findByText('Forbidden')).toBeInTheDocument()
    expect(screen.getByText('You are not allowed to do this')).toBeInTheDocument()
  })
})
