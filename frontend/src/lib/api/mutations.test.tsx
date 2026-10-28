import { act, renderHook, waitFor } from '@testing-library/react'
import { delay, http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { useEditorStore } from '@/lib/stores/editor-store'
import { API, layoutFixture, layoutWire } from '@/test/fixtures'
import { createTestClient, wrapperFor } from '@/test/query-wrapper'
import { server } from '@/test/setup'
import {
  TEMP_ID_PREFIX,
  queryKeys,
  useCreateAsset,
  useDeleteAsset,
  useDraftLayout,
  useLayoutVersions,
  useDuplicateAsset,
  usePublishLayout,
  useRestoreVersion,
  useStartBlankLayout,
  useUpdateAsset,
  type Layout,
} from '.'
import type { ApiError } from '@/lib/api-client'

const DRAFT_READ = `${API}/v1/ports/westports/layouts/draft`
const LAYOUT_ID = layoutFixture.versionId
const ASSETS_URL = `${API}/v1/layouts/${LAYOUT_ID}/assets`
/** A stored feature as the asset endpoints answer it. */
const feature = (id: string, rowVersion: number, extra: Record<string, unknown> = {}) => ({
  type: 'Feature',
  id,
  geometry: { type: 'Point', coordinates: [101.3, 2.95] },
  properties: { type: 'EXCAVATOR', rowVersion, ...extra },
})

const problem = (status: number, title: string) =>
  HttpResponse.json(
    { title, status },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )

beforeEach(() => useEditorStore.getState().reset())

function seededClient() {
  const client = createTestClient()
  client.setQueryData(queryKeys.draft('westports'), structuredClone(layoutFixture))
  return client
}

const draftOf = (client: ReturnType<typeof createTestClient>) =>
  client.getQueryData<Layout>(queryKeys.draft('westports'))

describe('useCreateAsset', () => {
  it('shows the new asset at once, then settles on a refetch', async () => {
    const client = seededClient()
    let created = false
    server.use(
      http.post(ASSETS_URL, async () => {
        await delay(300)
        created = true
        return HttpResponse.json({ feature: feature('a-3', 0), warnings: [] }, { status: 201 })
      }),
      http.get(DRAFT_READ, () =>
        HttpResponse.json(
          layoutWire({
            ...layoutFixture,
            assets: created
              ? [...layoutFixture.assets, { id: 'a-3', typeCode: 'EXCAVATOR', rowVersion: 0 }]
              : layoutFixture.assets,
          }),
        ),
      ),
    )
    const observer = renderHook(() => useDraftLayout('westports'), { wrapper: wrapperFor(client) })
    await waitFor(() => expect(observer.result.current.isFetching).toBe(false))
    const { result } = renderHook(() => useCreateAsset('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate({ typeCode: 'EXCAVATOR' }))

    await waitFor(() => expect(draftOf(client)?.assets).toHaveLength(3))
    expect(draftOf(client)?.assets[2]?.id.startsWith(TEMP_ID_PREFIX)).toBe(true)
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    await waitFor(() => expect(draftOf(client)?.assets[2]?.id).toBe('a-3'))
  })

  it('removes the asset again when the server refuses', async () => {
    const client = seededClient()
    server.use(
      http.post(ASSETS_URL, () => problem(422, 'Validation failed')),
      http.get(DRAFT_READ, () => HttpResponse.json(layoutWire())),
    )
    const { result } = renderHook(() => useCreateAsset('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate({ typeCode: 'EXCAVATOR' }))

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(draftOf(client)?.assets).toHaveLength(2)
  })

  it('rejects an answer of the wrong shape and rolls back', async () => {
    const client = seededClient()
    server.use(
      http.post(ASSETS_URL, () => HttpResponse.json({ nope: true }, { status: 201 })),
      http.get(DRAFT_READ, () => HttpResponse.json(layoutWire())),
    )
    const { result } = renderHook(() => useCreateAsset('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate({ typeCode: 'EXCAVATOR' }))

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(draftOf(client)?.assets).toHaveLength(2)
  })
})

describe('useUpdateAsset', () => {
  it('applies the change at once and sends the row version in If-Match', async () => {
    const client = seededClient()
    let ifMatch: string | null = null
    let body: unknown
    server.use(
      http.patch(`${ASSETS_URL}/a-1`, async ({ request }) => {
        ifMatch = request.headers.get('If-Match')
        body = await request.json()
        await delay(30)
        return HttpResponse.json({
          feature: feature('a-1', 4, { name: 'Renamed' }),
          warnings: [],
        })
      }),
      http.get(DRAFT_READ, () => HttpResponse.json(layoutWire())),
    )
    const { result } = renderHook(() => useUpdateAsset('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate({ id: 'a-1', rowVersion: 3, changes: { name: 'Renamed' } }))

    await waitFor(() => expect(draftOf(client)?.assets[0]).toMatchObject({ name: 'Renamed' }))
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(ifMatch).toBe('"3"')
    expect(body).toEqual({ name: 'Renamed' })
  })

  it('puts the old value back on a 409 conflict', async () => {
    const client = seededClient()
    server.use(
      http.patch(`${ASSETS_URL}/a-1`, () => problem(409, 'Conflict')),
      http.get(DRAFT_READ, () => HttpResponse.json(layoutWire())),
    )
    const { result } = renderHook(() => useUpdateAsset('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate({ id: 'a-1', rowVersion: 3, changes: { name: 'Renamed' } }))

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(draftOf(client)?.assets[0]).toMatchObject({ name: 'Excavator 1' })
  })
})

describe('useDeleteAsset', () => {
  it('drops the asset at once and restores it when the delete fails', async () => {
    const client = seededClient()
    server.use(
      http.delete(`${ASSETS_URL}/a-2`, () => problem(409, 'Conflict')),
      http.get(DRAFT_READ, () => HttpResponse.json(layoutWire())),
    )
    const { result } = renderHook(() => useDeleteAsset('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate({ id: 'a-2', rowVersion: 1 }))

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(draftOf(client)?.assets.map((a) => a.id)).toEqual(['a-1', 'a-2'])
  })

  it('keeps the asset gone when the delete succeeds', async () => {
    const client = seededClient()
    let ifMatch: string | null = null
    server.use(
      http.delete(`${ASSETS_URL}/a-2`, ({ request }) => {
        ifMatch = request.headers.get('If-Match')
        return new HttpResponse(null, { status: 204 })
      }),
      http.get(DRAFT_READ, () =>
        HttpResponse.json(
          layoutWire({ ...layoutFixture, assets: layoutFixture.assets.slice(0, 1) }),
        ),
      ),
    )
    const { result } = renderHook(() => useDeleteAsset('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate({ id: 'a-2', rowVersion: 1 }))

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(ifMatch).toBe('"1"')
    await waitFor(() => expect(draftOf(client)?.assets.map((a) => a.id)).toEqual(['a-1']))
  })
})

describe('useDuplicateAsset', () => {
  it('duplicates the asset and shows it at once', async () => {
    const client = seededClient()
    let created = false
    server.use(
      http.post(`${ASSETS_URL}/a-1/duplicate`, async () => {
        await delay(300)
        created = true
        return HttpResponse.json({ feature: feature('a-3', 0), warnings: [] }, { status: 201 })
      }),
      http.get(DRAFT_READ, () =>
        HttpResponse.json(
          layoutWire({
            ...layoutFixture,
            assets: created
              ? [...layoutFixture.assets, { id: 'a-3', typeCode: 'EXCAVATOR', rowVersion: 0 }]
              : layoutFixture.assets,
          }),
        ),
      ),
    )
    const { result } = renderHook(() => useDuplicateAsset('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate('a-1'))

    await waitFor(() => expect(draftOf(client)?.assets).toHaveLength(3))
    expect(draftOf(client)?.assets[2]?.id.startsWith(TEMP_ID_PREFIX)).toBe(true)
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })

  it('rolls back when the server refuses', async () => {
    const client = seededClient()
    server.use(
      http.post(`${ASSETS_URL}/a-1/duplicate`, () => problem(422, 'Validation failed')),
      http.get(DRAFT_READ, () => HttpResponse.json(layoutWire())),
    )
    const { result } = renderHook(() => useDuplicateAsset('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate('a-1'))

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(draftOf(client)?.assets).toHaveLength(2)
  })
})

describe('usePublishLayout', () => {
  const publish = `${API}/v1/layouts/v-1/publish`
  const result = {
    versionNo: 2,
    publishedAt: '2026-10-28T02:00:00Z',
    publishedBy: 'pat',
    reviewedBy: 'rita',
    assetCount: 2,
  }

  it('posts the note and the reviewer, drops the draft and reads the versions again', async () => {
    const client = seededClient()
    let body: unknown
    let versionReads = 0
    server.use(
      http.post(publish, async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(result)
      }),
      http.get(`${API}/v1/ports/westports/layouts`, () => {
        versionReads += 1
        return HttpResponse.json([])
      }),
    )
    client.setQueryData(queryKeys.versions('westports'), [])
    const hook = renderHook(() => usePublishLayout('westports'), { wrapper: wrapperFor(client) })
    // an observer keeps the version list active so the invalidation reads it again
    renderHook(() => useLayoutVersions('westports'), { wrapper: wrapperFor(client) })

    act(() =>
      hook.result.current.mutate({ layoutId: 'v-1', note: 'Moved gate', reviewedBy: 'rita' }),
    )

    await waitFor(() => expect(hook.result.current.isSuccess).toBe(true))
    expect(body).toEqual({ note: 'Moved gate', reviewedBy: 'rita' })
    expect(hook.result.current.data).toMatchObject({ versionNo: 2, publishedBy: 'pat' })
    expect(draftOf(client)).toBeUndefined()
    await waitFor(() => expect(versionReads).toBeGreaterThan(1))
  })

  it('leaves out the reviewer when nobody is named', async () => {
    const client = seededClient()
    let body: unknown
    server.use(
      http.post(publish, async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ ...result, reviewedBy: null })
      }),
    )
    const { result: hook } = renderHook(() => usePublishLayout('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => hook.current.mutate({ layoutId: 'v-1', note: '', reviewedBy: null }))

    await waitFor(() => expect(hook.current.isSuccess).toBe(true))
    expect(body).toEqual({ note: '' })
  })

  it('keeps the draft when publishing is refused', async () => {
    const client = seededClient()
    server.use(
      http.post(publish, () => problem(422, 'Publishing blocked: 1 error(s). Fix them first.')),
    )
    const { result: hook } = renderHook(() => usePublishLayout('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => hook.current.mutate({ layoutId: 'v-1', note: '' }))

    await waitFor(() => expect(hook.current.isError).toBe(true))
    expect((hook.current.error as ApiError).title).toBe(
      'Publishing blocked: 1 error(s). Fix them first.',
    )
    expect(draftOf(client)?.status).toBe('DRAFT')
  })
})

describe('useRestoreVersion', () => {
  it('posts discardDraft and puts the new draft in the cache', async () => {
    const client = createTestClient()
    let body: unknown
    server.use(
      http.post(`${API}/v1/layouts/v-1/restore`, async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(
          {
            ...layoutWire({ ...layoutFixture, versionId: 'd-3', number: 3 }),
            message:
              'Revision 1 loaded. Asset IDs are unchanged. Save the draft or publish to keep it.',
          },
          { status: 201 },
        )
      }),
    )
    const { result } = renderHook(() => useRestoreVersion('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate({ versionId: 'v-1', discardDraft: true }))

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(body).toEqual({ discardDraft: true })
    expect(draftOf(client)).toMatchObject({ versionId: 'd-3', number: 3 })
  })

  it('does not discard an open draft unless asked', async () => {
    const client = seededClient()
    let body: unknown
    server.use(
      http.post(`${API}/v1/layouts/v-1/restore`, async ({ request }) => {
        body = await request.json()
        return problem(409, 'A draft already exists for this layout')
      }),
    )
    const { result } = renderHook(() => useRestoreVersion('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate({ versionId: 'v-1' }))

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(body).toEqual({ discardDraft: false })
    expect(draftOf(client)?.versionId).toBe('v-1')
  })
})

describe('useStartBlankLayout', () => {
  it('discards the open draft and creates a blank one', async () => {
    const client = seededClient()
    const calls: string[] = []
    server.use(
      http.delete(`${API}/v1/layouts/v-1`, () => {
        calls.push('delete')
        return new HttpResponse(null, { status: 204 })
      }),
      http.post(`${API}/v1/ports/westports/layouts`, async ({ request }) => {
        calls.push(`post ${JSON.stringify(await request.json())}`)
        return HttpResponse.json(
          layoutWire(
            { ...layoutFixture, versionId: 'd-2', number: 2, assets: [] },
            { basedOnVersionNo: null },
          ),
          { status: 201 },
        )
      }),
    )
    const { result } = renderHook(() => useStartBlankLayout('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate('v-1'))

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(calls).toEqual(['delete', 'post {"basedOn":"blank"}'])
    expect(draftOf(client)).toMatchObject({ versionId: 'd-2', basedOnVersionNo: null })
  })
})
