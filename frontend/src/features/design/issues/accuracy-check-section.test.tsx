import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { API, catalogueFixture } from '@/test/fixtures'
import { server } from '@/test/setup'
import { queryKeys, type AccuracyReport } from '@/lib/api'
import { AccuracyCheckSection } from './accuracy-check-section'

const LAYOUT_ID = 'v-1'

const REPORT: AccuracyReport = {
  ranAt: '2026-10-27T14:05:00Z',
  controlPoints: [
    { code: 'CP-MAIN-GATE', name: 'Main gate', residualM: 0.08, roundTripMm: 0.001 },
    { code: 'CP-COAST-00', name: 'Coastline 0', residualM: null, roundTripMm: 0.0004 },
  ],
  maxResidualM: 0.08,
  maxRoundTripMm: 0.001,
  countsByAccuracy: { SURVEYED: 0, VERIFIED: 0, OPERATOR: 0, APPROXIMATE: 301 },
  approximateCount: 301,
  note: 'The acceptance tolerance must be agreed with the customer; satellite tracing cannot give sub-metre accuracy.',
}

function renderSection(layoutId: string | undefined, seed?: AccuracyReport) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  if (seed && layoutId) client.setQueryData(queryKeys.accuracyReport(layoutId), seed)
  return render(
    <QueryClientProvider client={client}>
      <AccuracyCheckSection layoutId={layoutId} />
    </QueryClientProvider>,
  )
}

describe('AccuracyCheckSection', () => {
  it('says the check has not run yet when there is no cached report', () => {
    renderSection(LAYOUT_ID)
    expect(
      screen.getByText('Not run yet. Press Verify accuracy in the More menu.'),
    ).toBeInTheDocument()
  })

  it('shows the run time, control points, the largest residual and round trip note', () => {
    server.use(http.get(`${API}/v1/catalogue`, () => HttpResponse.json(catalogueFixture)))
    renderSection(LAYOUT_ID, REPORT)

    // The run time is the browser's own clock (like the History popover), so only the date part is fixed here.
    expect(
      screen.getByText(/^Run 27 Oct, \d{2}:\d{2} against 2 control points\.$/),
    ).toBeInTheDocument()
    expect(screen.getByText('Main gate (CP-MAIN-GATE)')).toBeInTheDocument()
    expect(screen.getByText('0.08 m')).toBeInTheDocument()
    expect(screen.getByText('n/a')).toBeInTheDocument()
    expect(screen.getByText('0.001 mm')).toBeInTheDocument()
    expect(
      screen.getByText(
        'Largest residual 0.08 m between the stored local position and the WGS84 point; largest projection round trip 0.001 mm. The acceptance tolerance must be agreed with the customer; satellite tracing cannot give sub-metre accuracy.',
      ),
    ).toBeInTheDocument()
  })

  it('lists the shape count of each accuracy class and the no-survey note', async () => {
    server.use(http.get(`${API}/v1/catalogue`, () => HttpResponse.json(catalogueFixture)))
    renderSection(LAYOUT_ID, REPORT)

    expect(await screen.findByText('301 shapes')).toBeInTheDocument()
    expect(
      screen.getByText(
        '301 shapes have no survey. They carry an Approximate label everywhere they are shown.',
      ),
    ).toBeInTheDocument()
  })
})
