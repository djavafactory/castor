import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { layoutWire, portFixture } from '@/test/fixtures'
import { wrapperFor } from '@/test/query-wrapper'
import { server } from '@/test/setup'
import { StatusStrip } from './status-strip'
import { ACCURACY_TIP, ENVELOPE_TIP, issuesText } from './status-text'

vi.mock('sonner', () => ({ toast: Object.assign(vi.fn(), { error: vi.fn() }) }))

const BASE = 'http://api.test/api/v1'

function answer(errors: number, warnings: number) {
  server.use(
    http.get(`${BASE}/ports/WESTPORTS`, () => HttpResponse.json(portFixture)),
    http.get(`${BASE}/ports/WESTPORTS/layouts/draft`, () =>
      HttpResponse.json(layoutWire({ versionId: 'v-1', number: 7, status: 'DRAFT', assets: [] })),
    ),
    http.post(`${BASE}/layouts/v-1/validate`, () =>
      HttpResponse.json({ errors, warnings, issues: [] }),
    ),
  )
}

function renderStrip() {
  return render(<StatusStrip />, { wrapper: wrapperFor() })
}

const strip = () => screen.getByRole('contentinfo', { name: 'Status' })

beforeEach(() => {
  vi.mocked(toast).mockClear()
  useEditorStore.getState().reset()
  useUiStore.setState({
    mode: 'design',
    viewMode: 'split',
    basemap: 'sentinel2',
    accuracyColouring: false,
    envelope: false,
    panelHidden: true,
    rightTab: 'inspector',
  })
})

describe('bottom strip', () => {
  it('has the controls of the ticket, left to right', async () => {
    answer(0, 2)
    renderStrip()
    await screen.findByText('0 errors, 2 warnings')
    const text = strip().textContent ?? ''
    const order = [
      'Cursor: -',
      'CRS WGS84 / metres',
      'Basemap',
      'Measure',
      'Accuracy',
      'Envelope',
      'Draft v7',
      'History (0)',
      '0 errors, 2 warnings',
    ].map((part) => text.indexOf(part))
    expect(order.every((at) => at >= 0)).toBe(true)
    expect(order).toEqual([...order].sort((a, b) => a - b))
    expect(strip()).toHaveClass('h-8')
  })

  it('explains the CRS with the measuring SRID of the port', async () => {
    answer(0, 0)
    renderStrip()
    await waitFor(() =>
      expect(
        within(strip())
          .getByText(/WGS84 \/ metres/)
          .closest('[title]'),
      ).toHaveAttribute(
        'title',
        'Stored as WGS84 (EPSG:4326, GeoJSON lon/lat order). Lengths and areas are computed in metres (EPSG:3380).',
      ),
    )
  })

  it('toggles the Measure tool and shows it is on', async () => {
    answer(0, 0)
    renderStrip()
    const button = screen.getByRole('button', { name: 'Measure' })
    expect(button).toHaveAttribute('title', 'Measure a distance (M)')
    expect(button).toHaveAttribute('aria-pressed', 'false')
    await userEvent.click(button)
    expect(useEditorStore.getState().tool?.kind).toBe('measure')
    expect(button).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(button)
    expect(useEditorStore.getState().tool).toBeNull()
  })

  it('switches the accuracy outlines and the envelope boxes, with their tooltips', async () => {
    answer(0, 0)
    renderStrip()
    const accuracy = screen.getByRole('checkbox', { name: 'Accuracy' })
    const envelope = screen.getByRole('checkbox', { name: 'Envelope' })
    expect(accuracy.closest('label')).toHaveAttribute('title', ACCURACY_TIP)
    expect(envelope.closest('label')).toHaveAttribute('title', ENVELOPE_TIP)
    await userEvent.click(accuracy)
    await userEvent.click(envelope)
    expect(useUiStore.getState()).toMatchObject({ accuracyColouring: true, envelope: true })
  })

  it('turns the issues button red with errors and opens the Issues tab', async () => {
    answer(1, 0)
    renderStrip()
    const button = await screen.findByRole('button', { name: '1 error, 0 warnings' })
    expect(button).toHaveClass('text-bad')
    await userEvent.click(button)
    expect(useUiStore.getState()).toMatchObject({ panelHidden: false, rightTab: 'issues' })
  })

  it('is not red with warnings only', async () => {
    answer(0, 3)
    renderStrip()
    expect(await screen.findByRole('button', { name: '0 errors, 3 warnings' })).not.toHaveClass(
      'text-bad',
    )
  })
})

describe('issuesText', () => {
  it('counts in words', () => {
    expect(issuesText(0, 0)).toBe('0 errors, 0 warnings')
    expect(issuesText(1, 1)).toBe('1 error, 1 warning')
    expect(issuesText(2, 5)).toBe('2 errors, 5 warnings')
  })
})
