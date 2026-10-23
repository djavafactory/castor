import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it } from 'vitest'
import { server } from '@/test/setup'
import { clearActiveFrame, hasActiveFrame, toLonLat } from '.'
import { toFrameParams, usePortFrame } from './use-port-frame'

const BASE = 'http://api.test/api'

afterEach(clearActiveFrame)

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

describe('usePortFrame', () => {
  it('maps the port record to frame parameters', () => {
    expect(toFrameParams({ origin: { lon: 101.3, lat: 2.9 }, rotDeg: -120 })).toEqual({
      lon0: 101.3,
      lat0: 2.9,
      rotDeg: -120,
    })
  })

  it('activates the frame served by GET /v1/ports/{code}', async () => {
    server.use(
      http.get(`${BASE}/v1/ports/WESTPORTS`, () =>
        HttpResponse.json({ origin: { lon: 101.3066304, lat: 2.9534057 }, rotDeg: -120.299 }),
      ),
    )
    const { result } = renderHook(() => usePortFrame(), { wrapper })
    expect(result.current).toBe(false)
    await waitFor(() => expect(result.current).toBe(true))
    expect(hasActiveFrame()).toBe(true)
    expect(toLonLat(0, 0)).toEqual([101.3066304, 2.9534057])
  })
})
