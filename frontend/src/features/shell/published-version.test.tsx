import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import type { ReactNode } from 'react'
import { toast } from 'sonner'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LayoutHeader } from '@/lib/api'
import { API, currentLayoutFixture, portFixture } from '@/test/fixtures'
import { server } from '@/test/setup'
import {
  formatPublishedAt,
  NEW_VERSION_MESSAGE,
  NEW_VERSION_TOAST_ID,
  publishedLabel,
} from './published-label'
import { NewVersionWatcher, PublishedVersion } from './published-version'

vi.mock('sonner', () => ({ toast: Object.assign(vi.fn(), { dismiss: vi.fn() }) }))

const CURRENT = `${API}/v1/ports/WESTPORTS/layouts/current`
const ETAG_KEY = ['layout', 'WESTPORTS', 'current', 'etag']
const header = currentLayoutFixture.layout as LayoutHeader

function current(etag: string, versionNo: number) {
  return HttpResponse.json(
    { ...currentLayoutFixture, layout: { ...currentLayoutFixture.layout, versionNo } },
    { headers: { ETag: etag } },
  )
}

function renderWith(ui: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return { client, ...render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>) }
}

beforeEach(() => {
  vi.mocked(toast).mockClear()
  vi.mocked(toast.dismiss).mockClear()
  server.use(http.get(`${API}/v1/ports/WESTPORTS`, () => HttpResponse.json(portFixture)))
})

describe('publishedLabel', () => {
  it('reads "Published v3 · 27 Oct 2026 14:05 by pia" in the port time zone', () => {
    expect(publishedLabel(header, 'Asia/Kuala_Lumpur')).toBe(
      'Published v3 · 27 Oct 2026 14:05 by pia',
    )
  })

  it('leaves out what the server did not send', () => {
    expect(publishedLabel({ ...header, publishedAt: null, publishedBy: null })).toBe('Published v3')
  })

  it('uses a two digit hour and gives back text that is not a date', () => {
    expect(formatPublishedAt('2026-01-05T00:07:00Z', 'UTC')).toBe('05 Jan 2026 00:07')
    expect(formatPublishedAt('soon')).toBe('soon')
  })
})

describe('PublishedVersion', () => {
  it('shows the version as muted text with the publish note as tooltip', async () => {
    server.use(http.get(CURRENT, () => current('"a"', 3)))
    renderWith(<PublishedVersion />)
    const label = await screen.findByText('Published v3 · 27 Oct 2026 14:05 by pia')
    expect(label).toHaveAttribute('title', 'Berth 4 extended')
    expect(label).toHaveClass('text-muted')
  })

  it('shows nothing while there is no published layout', async () => {
    server.use(
      http.get(CURRENT, () =>
        HttpResponse.json({ title: 'No layout', status: 404 }, { status: 404 }),
      ),
    )
    const { container } = renderWith(<PublishedVersion />)
    await waitFor(() => expect(container).toBeEmptyDOMElement())
  })
})

describe('NewVersionWatcher', () => {
  it('offers a reload once the ETag changes, and reloads the layout on click', async () => {
    let etag = '"a"'
    let versionNo = 3
    server.use(http.get(CURRENT, () => current(etag, versionNo)))
    const { client } = renderWith(
      <>
        <PublishedVersion />
        <NewVersionWatcher />
      </>,
    )
    await screen.findByText(/Published v3/)
    expect(toast).not.toHaveBeenCalled()

    etag = '"b"'
    versionNo = 4
    // The poll is a refetch of the ETag query; the test triggers it instead of waiting 60 s
    await act(() => client.refetchQueries({ queryKey: ETAG_KEY }))
    await waitFor(() => expect(toast).toHaveBeenCalledTimes(1))
    const [message, options] = vi.mocked(toast).mock.calls[0]!
    expect(message).toBe(NEW_VERSION_MESSAGE)
    expect(options).toMatchObject({
      id: NEW_VERSION_TOAST_ID,
      duration: Infinity,
      action: { label: 'Reload' },
    })
    // The picture does not change by itself
    expect(screen.getByText(/Published v3/)).toBeInTheDocument()

    await act(async () =>
      (options as unknown as { action: { onClick: () => void } }).action.onClick(),
    )
    expect(toast.dismiss).toHaveBeenCalledWith(NEW_VERSION_TOAST_ID)
    expect(await screen.findByText(/Published v4/)).toBeInTheDocument()
  })

  it('stays quiet while the ETag is unchanged', async () => {
    server.use(http.get(CURRENT, () => current('"a"', 3)))
    const { client } = renderWith(
      <>
        <PublishedVersion />
        <NewVersionWatcher />
      </>,
    )
    await screen.findByText(/Published v3/)
    await act(() => client.refetchQueries({ queryKey: ETAG_KEY }))
    expect(toast).not.toHaveBeenCalled()
  })

  it('polls once a minute', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let calls = 0
    server.use(
      http.get(CURRENT, () => {
        calls += 1
        return current('"a"', 3)
      }),
    )
    renderWith(<NewVersionWatcher />)
    // First the layout, then its ETag
    await vi.waitFor(() => expect(calls).toBe(2))
    await act(() => vi.advanceTimersByTimeAsync(59_000))
    expect(calls).toBe(2)
    await act(() => vi.advanceTimersByTimeAsync(2_000))
    await vi.waitFor(() => expect(calls).toBe(3))
    vi.useRealTimers()
  })
})
