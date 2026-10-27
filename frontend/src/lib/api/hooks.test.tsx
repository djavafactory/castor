import { renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { shouldRetry } from '@/app/query-client'
import {
  API,
  assetTypesFixture,
  catalogueFixture,
  controlPointsFixture,
  layoutFixture,
  portFixture,
  referenceFeaturesFixture,
} from '@/test/fixtures'
import { wrapperFor } from '@/test/query-wrapper'
import { server } from '@/test/setup'
import {
  BAD_SHAPE_TITLE,
  ResponseShapeError,
  useAccuracyReport,
  useAssetHistory,
  useAssetTypes,
  useCatalogue,
  useControlPoints,
  useDraftLayout,
  useLayoutVersions,
  usePort,
  usePublishedLayout,
  useReferenceFeatures,
  useSearch,
} from '.'

const WESTPORTS = `${API}/v1/ports/westports`

describe('reference data hooks', () => {
  it('usePort returns the validated port', async () => {
    server.use(http.get(WESTPORTS, () => HttpResponse.json(portFixture)))
    const { result } = renderHook(() => usePort('westports'), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.utcOffset).toBe('+08:00')
    expect(result.current.data?.frameRotDeg).toBe(-120.299)
  })

  it('usePort defaults to the configured port code', async () => {
    server.use(http.get(`${API}/v1/ports/WESTPORTS`, () => HttpResponse.json(portFixture)))
    const { result } = renderHook(() => usePort(), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })

  it('usePort reports a wrong shape as an error that is never retried', async () => {
    server.use(
      http.get(WESTPORTS, () => HttpResponse.json({ ...portFixture, frameRotDeg: 'sideways' })),
    )
    const { result } = renderHook(() => usePort('westports'), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isError).toBe(true))
    const error = result.current.error
    expect(error).toBeInstanceOf(ResponseShapeError)
    expect((error as ResponseShapeError).title).toBe(BAD_SHAPE_TITLE)
    expect((error as ResponseShapeError).detail).toContain('frameRotDeg')
    expect(shouldRetry(0, error)).toBe(false)
  })

  it('usePort surfaces a 404 Problem Details', async () => {
    server.use(
      http.get(`${API}/v1/ports/nowhere`, () =>
        HttpResponse.json(
          { title: 'Not found', status: 404, detail: "No port with code 'nowhere'" },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    const { result } = renderHook(() => usePort('nowhere'), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.error?.message).toBe("No port with code 'nowhere'")
  })

  it('useControlPoints returns the control point features', async () => {
    server.use(
      http.get(`${WESTPORTS}/control-points`, () => HttpResponse.json(controlPointsFixture)),
    )
    const { result } = renderHook(() => useControlPoints('westports'), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.features[0]?.properties.expected_local_x).toBe(-7.9)
  })

  it('useReferenceFeatures asks for the kinds and returns the shapes', async () => {
    let kind: string | null = null
    server.use(
      http.get(`${WESTPORTS}/reference-features`, ({ request }) => {
        kind = new URL(request.url).searchParams.get('kind')
        return HttpResponse.json(referenceFeaturesFixture)
      }),
    )
    const { result } = renderHook(() => useReferenceFeatures(['BUILDING', 'ROAD'], 'westports'), {
      wrapper: wrapperFor(),
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(kind).toBe('BUILDING,ROAD')
    expect(result.current.data?.features[0]?.properties.height_m).toBe(12)
  })

  it('useReferenceFeatures without kinds sends no kind filter', async () => {
    let search = 'unset'
    server.use(
      http.get(`${WESTPORTS}/reference-features`, ({ request }) => {
        search = new URL(request.url).search
        return HttpResponse.json(referenceFeaturesFixture)
      }),
    )
    const { result } = renderHook(() => useReferenceFeatures([], 'westports'), {
      wrapper: wrapperFor(),
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(search).toBe('')
  })

  it('useAssetTypes returns the registry and rejects a bad colour', async () => {
    server.use(http.get(`${API}/v1/asset-types`, () => HttpResponse.json(assetTypesFixture)))
    const ok = renderHook(() => useAssetTypes(), { wrapper: wrapperFor() })
    await waitFor(() => expect(ok.result.current.isSuccess).toBe(true))
    expect(ok.result.current.data?.[0]?.code).toBe('EXCAVATOR')

    server.use(
      http.get(`${API}/v1/asset-types`, () =>
        HttpResponse.json([{ ...assetTypesFixture[0], colourHex: 'orange' }]),
      ),
    )
    const bad = renderHook(() => useAssetTypes(), { wrapper: wrapperFor() })
    await waitFor(() => expect(bad.result.current.isError).toBe(true))
    expect(bad.result.current.error).toBeInstanceOf(ResponseShapeError)
  })

  it('useCatalogue returns the catalogue with its help text', async () => {
    server.use(http.get(`${API}/v1/catalogue`, () => HttpResponse.json(catalogueFixture)))
    const { result } = renderHook(() => useCatalogue(), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.densityNote).toBe('Densities are typical values, not a standard.')
  })
})

describe('layout hooks', () => {
  it('usePublishedLayout and useDraftLayout read their layouts', async () => {
    server.use(
      http.get(`${WESTPORTS}/layout/published`, () =>
        HttpResponse.json({ ...layoutFixture, status: 'PUBLISHED' }),
      ),
      http.get(`${WESTPORTS}/layout/draft`, () => HttpResponse.json(layoutFixture)),
    )
    const published = renderHook(() => usePublishedLayout('westports'), { wrapper: wrapperFor() })
    const draft = renderHook(() => useDraftLayout('westports'), { wrapper: wrapperFor() })
    await waitFor(() => expect(published.result.current.isSuccess).toBe(true))
    await waitFor(() => expect(draft.result.current.isSuccess).toBe(true))
    expect(published.result.current.data?.status).toBe('PUBLISHED')
    expect(draft.result.current.data?.assets).toHaveLength(2)
    expect(draft.result.current.data?.assets[0]).toMatchObject({ name: 'Excavator 1' })
  })

  it('useDraftLayout stays idle while disabled', () => {
    const { result } = renderHook(() => useDraftLayout('westports', false), {
      wrapper: wrapperFor(),
    })
    expect(result.current.fetchStatus).toBe('idle')
  })

  it('useLayoutVersions reads the version list', async () => {
    server.use(
      http.get(`${WESTPORTS}/layout/versions`, () =>
        HttpResponse.json([
          {
            id: 'v-1',
            number: 1,
            status: 'PUBLISHED',
            createdAt: '2026-10-01T00:00:00Z',
            publishedAt: null,
          },
        ]),
      ),
    )
    const { result } = renderHook(() => useLayoutVersions('westports'), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.[0]?.number).toBe(1)
  })

  it('useAssetHistory waits for an asset id', async () => {
    server.use(
      http.get(`${WESTPORTS}/assets/a-1/history`, () =>
        HttpResponse.json([
          { revision: 2, at: '2026-10-02T00:00:00Z', actor: 'ada', action: 'MOVE' },
        ]),
      ),
    )
    const idle = renderHook(() => useAssetHistory(null, 'westports'), { wrapper: wrapperFor() })
    expect(idle.result.current.fetchStatus).toBe('idle')

    const { result } = renderHook(() => useAssetHistory('a-1', 'westports'), {
      wrapper: wrapperFor(),
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.[0]?.actor).toBe('ada')
  })

  it('useAccuracyReport reads the counts per accuracy class', async () => {
    server.use(
      http.get(`${WESTPORTS}/accuracy-report`, () =>
        HttpResponse.json({ total: 3, byAccuracy: { APPROXIMATE: 3 } }),
      ),
    )
    const { result } = renderHook(() => useAccuracyReport('westports'), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.byAccuracy.APPROXIMATE).toBe(3)
  })

  it('useSearch sends nothing below two characters and the trimmed term above', async () => {
    let term: string | null = null
    server.use(
      http.get(`${WESTPORTS}/search`, ({ request }) => {
        term = new URL(request.url).searchParams.get('q')
        return HttpResponse.json([{ kind: 'ASSET', id: 'a-1', label: 'Excavator 1' }])
      }),
    )
    const short = renderHook(() => useSearch(' a ', 'westports'), { wrapper: wrapperFor() })
    expect(short.result.current.fetchStatus).toBe('idle')

    const { result } = renderHook(() => useSearch(' exc ', 'westports'), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(term).toBe('exc')
    expect(result.current.data?.[0]?.label).toBe('Excavator 1')
  })
})
