import { renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { shouldRetry } from '@/app/query-client'
import {
  API,
  assetTypesFixture,
  catalogueFixture,
  controlPointsFixture,
  currentLayoutFixture,
  layoutFixture,
  layoutWire,
  portFixture,
  referenceFeaturesFixture,
} from '@/test/fixtures'
import { createTestClient, wrapperFor } from '@/test/query-wrapper'
import { server } from '@/test/setup'
import {
  BAD_SHAPE_TITLE,
  ResponseShapeError,
  useAccuracyReport,
  useAssetHistory,
  useAssetTypes,
  useCurrentLayout,
  useNewLayoutVersion,
  useCatalogue,
  useControlPoints,
  useDraftLayout,
  useLayoutDiff,
  useLayoutVersions,
  useReviewers,
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
      http.get(`${WESTPORTS}/layouts/current`, () =>
        HttpResponse.json(layoutWire({ ...layoutFixture, status: 'PUBLISHED' })),
      ),
      http.get(`${WESTPORTS}/layouts/draft`, () => HttpResponse.json(layoutWire())),
    )
    const published = renderHook(() => usePublishedLayout('westports'), { wrapper: wrapperFor() })
    const draft = renderHook(() => useDraftLayout('westports'), { wrapper: wrapperFor() })
    await waitFor(() => expect(published.result.current.isSuccess).toBe(true))
    await waitFor(() => expect(draft.result.current.isSuccess).toBe(true))
    expect(published.result.current.data?.status).toBe('PUBLISHED')
    expect(draft.result.current.data?.assets).toHaveLength(2)
    expect(draft.result.current.data?.assets[0]).toMatchObject({
      id: 'a-1',
      typeCode: 'EXCAVATOR',
      rowVersion: 3,
      name: 'Excavator 1',
    })
    expect(draft.result.current.data).toMatchObject({
      versionId: 'v-1',
      number: 1,
      basedOnVersionNo: 1,
      updatedBy: 'grace',
    })
  })

  it('useDraftLayout stays idle while disabled', () => {
    const { result } = renderHook(() => useDraftLayout('westports', false), {
      wrapper: wrapperFor(),
    })
    expect(result.current.fetchStatus).toBe('idle')
  })

  it('useLayoutVersions reads the version list newest first', async () => {
    let limit: string | null = null
    server.use(
      http.get(`${WESTPORTS}/layouts`, ({ request }) => {
        limit = new URL(request.url).searchParams.get('limit')
        return HttpResponse.json([
          {
            id: 'v-2',
            versionNo: 2,
            status: 'PUBLISHED',
            createdBy: 'ada',
            createdAt: '2026-10-02T00:00:00Z',
            publishedBy: 'pat',
            publishedAt: '2026-10-02T01:00:00Z',
            reviewedBy: 'rita',
            restoredFromVersionNo: 1,
            note: 'Moved gate',
            assetCount: 301,
          },
        ])
      }),
    )
    const { result } = renderHook(() => useLayoutVersions('westports'), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.[0]).toMatchObject({ versionNo: 2, reviewedBy: 'rita' })
    expect(limit).toBe('100')
  })

  it('useAssetHistory waits for an asset id', async () => {
    server.use(
      http.get(`${API}/v1/assets/a-1/history`, () =>
        HttpResponse.json([
          {
            versionId: 'v-2',
            versionNo: 2,
            status: 'PUBLISHED',
            name: 'Hall',
            geometry: 'Polygon, 1,234 m2',
            changedBy: 'ada',
            changedAt: '2026-10-02T00:00:00Z',
          },
        ]),
      ),
    )
    const idle = renderHook(() => useAssetHistory(null, 'westports'), { wrapper: wrapperFor() })
    expect(idle.result.current.fetchStatus).toBe('idle')

    const { result } = renderHook(() => useAssetHistory('a-1', 'westports'), {
      wrapper: wrapperFor(),
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.[0]?.changedBy).toBe('ada')
  })

  it('useLayoutDiff asks for the difference against another version and waits for both ids', async () => {
    let against: string | null = null
    server.use(
      http.get(`${API}/v1/layouts/d-1/diff`, ({ request }) => {
        against = new URL(request.url).searchParams.get('against')
        return HttpResponse.json({
          added: [],
          removed: [{ assetId: 'a-2', code: 'B', name: 'B', type: 'BUILDING' }],
          changed: [{ assetId: 'a-1', code: 'A', name: 'A', fields: ['geometry'] }],
        })
      }),
    )
    const idle = renderHook(() => useLayoutDiff('d-1', null), { wrapper: wrapperFor() })
    expect(idle.result.current.fetchStatus).toBe('idle')

    const { result } = renderHook(() => useLayoutDiff('d-1', 'v-1'), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(against).toBe('v-1')
    expect(result.current.data?.changed[0]?.fields).toEqual(['geometry'])
  })

  it('useReviewers reads the people who may review', async () => {
    server.use(
      http.get(`${API}/v1/reviewers`, () =>
        HttpResponse.json([{ username: 'rita', displayName: 'Rita' }]),
      ),
    )
    const { result } = renderHook(() => useReviewers(), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.[0]?.username).toBe('rita')
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
        return HttpResponse.json([
          {
            kind: 'ASSET',
            id: 'a-1',
            label: 'Excavator 1',
            sublabel: 'Excavator',
            lon: 101.3,
            lat: 2.95,
            bbox: [101.3, 2.95, 101.3, 2.95],
          },
        ])
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

describe('useSearch (GET search)', () => {
  const hit = {
    kind: 'CONTROL_POINT',
    id: 'CP-MAIN-GATE',
    label: 'Main gate',
    sublabel: 'Control point',
    lon: 101.3093602,
    lat: 2.9518844,
    bbox: [101.3093602, 2.9518844, 101.3093602, 2.9518844],
  }

  it('sends the layout whose assets are searched, when there is one', async () => {
    let url: URL | null = null
    server.use(
      http.get(`${WESTPORTS}/search`, ({ request }) => {
        url = new URL(request.url)
        return HttpResponse.json([hit])
      }),
    )
    const { result } = renderHook(() => useSearch('gate', 'westports', 'draft-7'), {
      wrapper: wrapperFor(),
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(url!.searchParams.get('q')).toBe('gate')
    expect(url!.searchParams.get('layoutId')).toBe('draft-7')
    expect(result.current.data?.[0]).toMatchObject({ kind: 'CONTROL_POINT', lat: 2.9518844 })
  })

  it('sends no layoutId for the published layout', async () => {
    let url: URL | null = null
    server.use(
      http.get(`${WESTPORTS}/search`, ({ request }) => {
        url = new URL(request.url)
        return HttpResponse.json([])
      }),
    )
    const { result } = renderHook(() => useSearch('gate', 'westports'), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(url!.searchParams.has('layoutId')).toBe(false)
  })

  it('refuses an answer of the wrong shape', async () => {
    server.use(
      http.get(`${WESTPORTS}/search`, () =>
        HttpResponse.json([{ kind: 'GOOGLE', id: 'x', label: 'x' }]),
      ),
    )
    const { result } = renderHook(() => useSearch('gate', 'westports'), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.error).toBeInstanceOf(ResponseShapeError)
  })

  it('keeps nothing of what was typed once the box no longer shows it', async () => {
    server.use(http.get(`${WESTPORTS}/search`, () => HttpResponse.json([hit])))
    const client = createTestClient()
    const { result, unmount } = renderHook(() => useSearch('gate', 'westports'), {
      wrapper: wrapperFor(client),
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(client.getQueryCache().findAll({ queryKey: ['search'] })).toHaveLength(1)
    unmount()
    await waitFor(() =>
      expect(client.getQueryCache().findAll({ queryKey: ['search'] })).toHaveLength(0),
    )
  })
})

describe('published layout (GET layouts/current)', () => {
  it('useCurrentLayout returns the validated layout with its ETag', async () => {
    server.use(
      http.get(`${WESTPORTS}/layouts/current`, () =>
        HttpResponse.json(currentLayoutFixture, { headers: { ETag: '"v3-0"' } }),
      ),
    )
    const { result } = renderHook(() => useCurrentLayout('westports'), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.etag).toBe('"v3-0"')
    expect(result.current.data?.data.layout).toMatchObject({ versionNo: 3, publishedBy: 'pia' })
    expect(result.current.data?.data.features.features).toHaveLength(6)
  })

  it('useCurrentLayout refuses a layout of an unknown shape', async () => {
    server.use(http.get(`${WESTPORTS}/layouts/current`, () => HttpResponse.json({ layout: {} })))
    const { result } = renderHook(() => useCurrentLayout('westports'), { wrapper: wrapperFor() })
    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.error).toBeInstanceOf(ResponseShapeError)
  })

  it('useNewLayoutVersion is true only when the server ETag differs from the loaded one', async () => {
    server.use(
      http.get(`${WESTPORTS}/layouts/current`, () =>
        HttpResponse.json(currentLayoutFixture, { headers: { ETag: '"v4-0"' } }),
      ),
    )
    const same = renderHook(() => useNewLayoutVersion('"v4-0"', 'westports'), {
      wrapper: wrapperFor(),
    })
    const older = renderHook(() => useNewLayoutVersion('"v3-0"', 'westports'), {
      wrapper: wrapperFor(),
    })
    const unloaded = renderHook(() => useNewLayoutVersion(null, 'westports'), {
      wrapper: wrapperFor(),
    })
    await waitFor(() => expect(older.result.current).toBe(true))
    expect(same.result.current).toBe(false)
    expect(unloaded.result.current).toBe(false)
  })
})
