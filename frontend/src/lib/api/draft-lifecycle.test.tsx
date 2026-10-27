import { act, renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useEditorStore } from '@/lib/stores/editor-store'
import { API, layoutFixture, layoutWire } from '@/test/fixtures'
import { createTestClient, wrapperFor } from '@/test/query-wrapper'
import { server } from '@/test/setup'
import {
  conflictMessage,
  queryKeys,
  useCreateDraft,
  useDiscardDraft,
  useUpdateAsset,
  type Layout,
} from '.'

vi.mock('sonner', () => ({ toast: Object.assign(vi.fn(), { error: vi.fn() }) }))

const PORT = `${API}/v1/ports/westports`
const problem = (status: number, title: string) =>
  HttpResponse.json(
    { title, status },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )

beforeEach(() => {
  useEditorStore.getState().reset()
  vi.mocked(toast).mockClear()
})

describe('useCreateDraft', () => {
  it('posts basedOn and puts the new draft in the cache', async () => {
    const client = createTestClient()
    let body: unknown
    server.use(
      http.post(`${PORT}/layouts`, async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(
          layoutWire(layoutFixture, { versionNo: 2, basedOnVersionNo: null }),
          {
            status: 201,
          },
        )
      }),
    )
    const { result } = renderHook(() => useCreateDraft('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate('blank'))

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(body).toEqual({ basedOn: 'blank' })
    const cached = client.getQueryData<Layout>(queryKeys.draft('westports'))
    expect(cached).toMatchObject({ number: 2, basedOnVersionNo: null })
  })

  it('reports a 409 as an error and leaves the cache alone', async () => {
    const client = createTestClient()
    server.use(http.post(`${PORT}/layouts`, () => problem(409, 'Conflict')))
    const { result } = renderHook(() => useCreateDraft('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate('published'))

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(client.getQueryData(queryKeys.draft('westports'))).toBeUndefined()
  })
})

describe('useDiscardDraft', () => {
  it('deletes the version and drops the cached draft', async () => {
    const client = createTestClient()
    client.setQueryData(queryKeys.draft('westports'), structuredClone(layoutFixture))
    let deleted = ''
    server.use(
      http.delete(`${API}/v1/layouts/:id`, ({ params }) => {
        deleted = String(params.id)
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const { result } = renderHook(() => useDiscardDraft('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate('v-1'))

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(deleted).toBe('v-1')
    expect(client.getQueryData(queryKeys.draft('westports'))).toBeUndefined()
  })
})

describe('saving into the shared draft', () => {
  function seeded() {
    const client = createTestClient()
    client.setQueryData(queryKeys.draft('westports'), structuredClone(layoutFixture))
    return client
  }
  const patch = { id: 'a-1', rowVersion: 3, changes: { name: 'Moved' } }

  it('on 409 says who changed the asset, reloads the draft and does not mark the save failed', async () => {
    const client = seeded()
    server.use(
      http.patch(`${PORT}/layout/draft/assets/a-1`, () => problem(409, 'Conflict')),
      http.get(`${PORT}/layouts/draft`, () =>
        HttpResponse.json(
          layoutWire(
            {
              ...layoutFixture,
              assets: [{ id: 'a-1', typeCode: 'EXCAVATOR', rowVersion: 4, name: 'Theirs' }],
            },
            { updatedBy: 'grace' },
          ),
        ),
      ),
    )
    const { result } = renderHook(() => useUpdateAsset('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate(patch))

    await waitFor(() => expect(toast).toHaveBeenCalledWith(conflictMessage('grace')))
    expect(conflictMessage('grace')).toBe(
      'This asset was changed by grace. Reloaded the latest version.',
    )
    expect(useEditorStore.getState().failedSaves).toEqual([])
    expect(useEditorStore.getState().pendingSaves).toEqual([])
    expect(client.getQueryData<Layout>(queryKeys.draft('westports'))?.assets).toEqual([
      expect.objectContaining({ id: 'a-1', rowVersion: 4, name: 'Theirs' }),
    ])
  })

  it('tracks a failed save for the status badge and Retry sends it again', async () => {
    const client = seeded()
    let calls = 0
    server.use(
      http.patch(`${PORT}/layout/draft/assets/a-1`, () => {
        calls += 1
        return calls === 1
          ? problem(500, 'Boom')
          : HttpResponse.json({ id: 'a-1', typeCode: 'EXCAVATOR', rowVersion: 4 })
      }),
      http.get(`${PORT}/layouts/draft`, () => HttpResponse.json(layoutWire())),
    )
    const { result } = renderHook(() => useUpdateAsset('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate(patch))
    await waitFor(() => expect(useEditorStore.getState().failedSaves).toEqual(['a-1']))
    expect(useEditorStore.getState().pendingSaves).toEqual([])

    act(() => useEditorStore.getState().retryFailedSaves())

    await waitFor(() => expect(calls).toBe(2))
    await waitFor(() => expect(useEditorStore.getState().failedSaves).toEqual([]))
    await waitFor(() => expect(useEditorStore.getState().pendingSaves).toEqual([]))
  })

  it('is pending while the request runs', async () => {
    const client = seeded()
    let release = () => {}
    const gate = new Promise<void>((resolve) => (release = resolve))
    server.use(
      http.patch(`${PORT}/layout/draft/assets/a-1`, async () => {
        await gate
        return HttpResponse.json({ id: 'a-1', typeCode: 'EXCAVATOR', rowVersion: 4 })
      }),
      http.get(`${PORT}/layouts/draft`, () => HttpResponse.json(layoutWire())),
    )
    const { result } = renderHook(() => useUpdateAsset('westports'), {
      wrapper: wrapperFor(client),
    })

    act(() => result.current.mutate(patch))
    await waitFor(() => expect(useEditorStore.getState().pendingSaves).toEqual(['a-1']))

    release()
    await waitFor(() => expect(useEditorStore.getState().pendingSaves).toEqual([]))
  })
})
