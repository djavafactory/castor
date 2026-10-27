import { act, renderHook, waitFor } from '@testing-library/react'
import { delay, http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { API, layoutFixture } from '@/test/fixtures'
import { createTestClient, wrapperFor } from '@/test/query-wrapper'
import { server } from '@/test/setup'
import {
  TEMP_ID_PREFIX,
  queryKeys,
  useCreateAsset,
  useDeleteAsset,
  useDraftLayout,
  usePublishLayout,
  useUpdateAsset,
  type Layout,
} from '.'

const DRAFT = `${API}/v1/ports/westports/layout/draft`
const problem = (status: number, title: string) =>
  HttpResponse.json(
    { title, status },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )

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
      http.post(`${DRAFT}/assets`, async () => {
        await delay(300)
        created = true
        return HttpResponse.json(
          { id: 'a-3', typeCode: 'EXCAVATOR', rowVersion: 0 },
          { status: 201 },
        )
      }),
      http.get(DRAFT, () =>
        HttpResponse.json({
          ...layoutFixture,
          assets: created
            ? [...layoutFixture.assets, { id: 'a-3', typeCode: 'EXCAVATOR', rowVersion: 0 }]
            : layoutFixture.assets,
        }),
      ),
    )
    // the draft is on screen, so something observes it and refetches when the mutation settles
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
      http.post(`${DRAFT}/assets`, () => problem(422, 'Validation failed')),
      http.get(DRAFT, () => HttpResponse.json(layoutFixture)),
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
      http.post(`${DRAFT}/assets`, () => HttpResponse.json({ nope: true }, { status: 201 })),
      http.get(DRAFT, () => HttpResponse.json(layoutFixture)),
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
      http.patch(`${DRAFT}/assets/a-1`, async ({ request }) => {
        ifMatch = request.headers.get('If-Match')
        body = await request.json()
        await delay(30)
        return HttpResponse.json({
          id: 'a-1',
          typeCode: 'EXCAVATOR',
          rowVersion: 4,
          name: 'Renamed',
        })
      }),
      http.get(DRAFT, () => HttpResponse.json(layoutFixture)),
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
      http.patch(`${DRAFT}/assets/a-1`, () => problem(409, 'Conflict')),
      http.get(DRAFT, () => HttpResponse.json(layoutFixture)),
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
      http.delete(`${DRAFT}/assets/a-2`, () => problem(409, 'Conflict')),
      http.get(DRAFT, () => HttpResponse.json(layoutFixture)),
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
      http.delete(`${DRAFT}/assets/a-2`, ({ request }) => {
        ifMatch = request.headers.get('If-Match')
        return new HttpResponse(null, { status: 204 })
      }),
      http.get(DRAFT, () =>
        HttpResponse.json({ ...layoutFixture, assets: layoutFixture.assets.slice(0, 1) }),
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

describe('usePublishLayout', () => {
  const publish = `${API}/v1/ports/westports/layout/publish`

  it('moves the draft into the published cache at once', async () => {
    const client = seededClient()
    server.use(
      http.post(publish, async () => {
        await delay(30)
        return HttpResponse.json({ ...layoutFixture, status: 'PUBLISHED' })
      }),
      http.get(`${API}/v1/ports/westports/layout/published`, () =>
        HttpResponse.json({ ...layoutFixture, status: 'PUBLISHED' }),
      ),
      http.get(`${API}/v1/ports/westports/layout/versions`, () => HttpResponse.json([])),
    )
    const { result } = renderHook(() => usePublishLayout('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate())

    await waitFor(() =>
      expect(client.getQueryData<Layout>(queryKeys.published('westports'))?.status).toBe(
        'PUBLISHED',
      ),
    )
    expect(draftOf(client)).toBeUndefined()
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })

  it('brings the draft back when publishing is refused', async () => {
    const client = seededClient()
    server.use(
      http.post(publish, () => problem(422, 'Publish blocked')),
      http.get(DRAFT, () => HttpResponse.json(layoutFixture)),
    )
    const { result } = renderHook(() => usePublishLayout('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate())

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(draftOf(client)?.status).toBe('DRAFT')
    expect(client.getQueryData(queryKeys.published('westports'))).toBeUndefined()
  })
})
