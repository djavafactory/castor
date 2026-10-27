import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { useMapStore } from '@/components/map/map-store'
import { useCameraStore } from '@/components/scene/stage/view-state'
import { setActiveFrame } from '@/lib/geo'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { layoutWire } from '@/test/fixtures'
import { server } from '@/test/setup'
import { OUTSIDE_PORT_MESSAGE, OUT_OF_RANGE_MESSAGE } from './coordinates'
import { PlaceSearch, SEARCH_PLACEHOLDER } from './place-search'

const BASE = 'http://api.test/api/v1/ports/WESTPORTS'

const BERTH_2 = {
  kind: 'ASSET',
  id: 'asset-b2',
  label: 'Berth 2',
  sublabel: 'Berth',
  lon: 101.306585,
  lat: 2.9533276,
  bbox: [101.3056774, 2.951766, 101.3074926, 2.9548892],
}
const MAIN_GATE = {
  kind: 'CONTROL_POINT',
  id: 'CP-MAIN-GATE',
  label: 'Westport Conventional Main Gate (map listing)',
  sublabel: 'Control point',
  lon: 101.3093602,
  lat: 2.9518844,
  bbox: [101.3093602, 2.9518844, 101.3093602, 2.9518844],
}
const SHED = {
  kind: 'REFERENCE',
  id: 'way/1001',
  label: 'Shed A',
  sublabel: 'Building (OpenStreetMap)',
  lon: 101.3072,
  lat: 2.9531,
  bbox: [101.307, 2.953, 101.3074, 2.9533],
}

let searches: URL[] = []

function answer(...hits: unknown[]) {
  server.use(
    http.get(`${BASE}/search`, ({ request }) => {
      searches.push(new URL(request.url))
      return HttpResponse.json(hits)
    }),
  )
}

function renderSearch() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  server.use(
    http.get(`${BASE}/layouts/draft`, () =>
      HttpResponse.json(
        layoutWire({ versionId: 'draft-7', number: 7, status: 'DRAFT', assets: [] }),
      ),
    ),
  )
  return render(
    <QueryClientProvider client={client}>
      <PlaceSearch />
    </QueryClientProvider>,
  )
}

const box = () => screen.getByRole('combobox', { name: 'Search places' })

beforeEach(() => {
  searches = []
  setActiveFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 })
  useSelectionStore.getState().clear()
  useMapStore.setState({
    view: { x: 0, y: 0, k: 0.5 },
    size: { width: 1000, height: 600 },
    pin: null,
    placed: true,
  })
  useCameraStore.setState({ command: null })
})

describe('place search box', () => {
  it('has the placeholder of the ticket', () => {
    renderSearch()
    expect(SEARCH_PLACEHOLDER).toBe('Search a place, a shape, or paste lat, lon')
    expect(box()).toHaveAttribute('placeholder', SEARCH_PLACEHOLDER)
  })

  it('asks the server nothing for fewer than two characters', async () => {
    answer(BERTH_2)
    renderSearch()
    await userEvent.type(box(), 'b')
    await new Promise((r) => setTimeout(r, 350))
    expect(searches).toEqual([])
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('looks the text up in the draft and lists label and type', async () => {
    answer(BERTH_2, MAIN_GATE)
    renderSearch()
    await userEvent.type(box(), 'berth 2')

    const options = await screen.findAllByRole('option')
    expect(options).toHaveLength(2)
    expect(options[0]).toHaveTextContent('Berth 2')
    expect(options[0]).toHaveTextContent('Berth')
    expect(options[1]).toHaveTextContent('Westport Conventional Main Gate')
    expect(options[1]).toHaveTextContent('Control point')
    const last = searches[searches.length - 1]
    expect(last.searchParams.get('q')).toBe('berth 2')
    expect(last.searchParams.get('layoutId')).toBe('draft-7')
    // Nothing is asked for the letters on the way, only for the text that stayed
    expect(searches.every((s) => (s.searchParams.get('q') ?? '').length >= 2)).toBe(true)
  })

  it('goes to the first result on Enter: a shape is selected and the map zooms to it', async () => {
    answer(BERTH_2, MAIN_GATE)
    renderSearch()
    await userEvent.type(box(), 'berth 2')
    await screen.findAllByRole('option')

    await userEvent.keyboard('{Enter}')

    expect(useSelectionStore.getState().selectedAssetId).toBe('asset-b2')
    // The map moved to the berth, which is about 400 m long: the view is fitted, not left at 0.5
    const { view } = useMapStore.getState()
    expect(view.x).not.toBe(0)
    expect(view.k).toBeGreaterThan(0.5)
    expect(useCameraStore.getState().command).toMatchObject({ type: 'focus' })
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('selects an OpenStreetMap shape as a reference', async () => {
    answer(SHED)
    renderSearch()
    await userEvent.type(box(), 'shed')
    await userEvent.click(await screen.findByRole('option', { name: /Shed A/ }))

    expect(useSelectionStore.getState().selectedReferenceId).toBe('way/1001')
    expect(useSelectionStore.getState().selectedAssetId).toBeNull()
  })

  it('goes to a place: centres the map at 0.6 or more, drops the pin, focuses the 3D camera', async () => {
    answer(MAIN_GATE)
    renderSearch()
    await userEvent.type(box(), 'gate')
    await userEvent.click(await screen.findByRole('option', { name: /Main Gate/ }))

    const { view, pin } = useMapStore.getState()
    expect(view.x).toBeCloseTo(-7.9, 0)
    expect(view.y).toBeCloseTo(346.9, 0)
    expect(view.k).toBe(0.6)
    expect(pin).not.toBeNull()
    expect(useSelectionStore.getState().selectedAssetId).toBeNull()
    expect(useCameraStore.getState().command).toMatchObject({ type: 'focus' })
  })

  it('keeps a closer scale when the map is already zoomed in', async () => {
    answer(MAIN_GATE)
    useMapStore.setState({ view: { x: 0, y: 0, k: 3 } })
    renderSearch()
    await userEvent.type(box(), 'gate')
    await userEvent.click(await screen.findByRole('option', { name: /Main Gate/ }))
    expect(useMapStore.getState().view.k).toBe(3)
  })

  it('moves through the list with the arrow keys', async () => {
    answer(BERTH_2, MAIN_GATE)
    renderSearch()
    await userEvent.type(box(), 'ga')
    await screen.findAllByRole('option')

    await userEvent.keyboard('{ArrowDown}{Enter}')

    expect(useSelectionStore.getState().selectedAssetId).toBeNull()
    expect(useMapStore.getState().pin).not.toBeNull()
  })

  it('closes the list on Escape and keeps the text', async () => {
    answer(BERTH_2)
    renderSearch()
    await userEvent.type(box(), 'berth')
    await screen.findByRole('listbox')

    await userEvent.keyboard('{Escape}')

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(box()).toHaveValue('berth')
  })

  it('says so when nothing is found, and when the search fails', async () => {
    answer()
    renderSearch()
    await userEvent.type(box(), 'zzzz')
    expect(await screen.findByText('No places found.')).toBeInTheDocument()
  })

  it('says so when the search cannot be reached', async () => {
    server.use(http.get(`${BASE}/search`, () => new HttpResponse(null, { status: 500 })))
    renderSearch()
    await userEvent.type(box(), 'berth')
    expect(await screen.findByText('Search is not available right now.')).toBeInTheDocument()
  })
})

describe('pasted coordinates', () => {
  it('offer "Go to <lat>, <lon> (lat, lon)" without asking the server', async () => {
    answer(BERTH_2)
    renderSearch()
    await userEvent.type(box(), '2.9518844, 101.3093602')

    expect(
      await screen.findByRole('option', { name: 'Go to 2.9518844, 101.3093602 (lat, lon)' }),
    ).toBeInTheDocument()
    await new Promise((r) => setTimeout(r, 350))
    expect(searches).toEqual([])
  })

  it('land on the main gate', async () => {
    renderSearch()
    await userEvent.type(box(), '2.9518844, 101.3093602{Enter}')

    const { view, pin } = useMapStore.getState()
    expect(view.x).toBeCloseTo(-7.9, 0)
    expect(view.y).toBeCloseTo(346.9, 0)
    expect(view.k).toBeGreaterThanOrEqual(0.6)
    expect(pin).toMatchObject({ x: view.x, y: view.y })
    expect(useCameraStore.getState().command).toMatchObject({ type: 'focus' })
  })

  it('explain a latitude or longitude out of range', async () => {
    renderSearch()
    await userEvent.type(box(), '95, 101.3')
    expect(await screen.findByText(OUT_OF_RANGE_MESSAGE)).toBeInTheDocument()
    await userEvent.keyboard('{Enter}')
    expect(useMapStore.getState().pin).toBeNull()
  })

  it('explain a place more than 20 km from the origin', async () => {
    renderSearch()
    await userEvent.type(box(), '3.139, 101.6869')
    expect(await screen.findByText(OUTSIDE_PORT_MESSAGE)).toBeInTheDocument()
    await waitFor(() => expect(searches).toEqual([]))
  })
})
