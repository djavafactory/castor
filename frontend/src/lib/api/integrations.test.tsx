import { QueryClient } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { basicCredential, useAuthStore } from '@/lib/stores/auth-store'
import { server } from '@/test/setup'
import { wrapperFor } from '@/test/query-wrapper'
import { BAD_SHAPE_TITLE } from './validated'
import {
  integrationKeys,
  useCloseIdentityMapping,
  useCreateIdentityMapping,
  useCreateIntegrationKey,
  useIdentityMappings,
  useIntegrationSources,
  useQuarantine,
  useQuarantineDetail,
  useReplayRecord,
  useRevokeIntegrationKey,
  useRotateIntegrationKey,
} from './integrations'

const BASE = 'http://api.test/api/v1/ports/WESTPORTS/integrations'

const ENTRY = {
  id: 7,
  receptionId: 'r-1',
  source: 'cbas',
  sourceEventId: 'E-1',
  reasonCode: 'UNKNOWN_MAPPING',
  detail: 'No mapping',
  receivedAt: '2026-10-29T08:00:00Z',
  quarantinedAt: '2026-10-29T08:00:01Z',
  replays: 0,
}

beforeEach(() => {
  useAuthStore.getState().signIn({
    username: 'ada',
    displayName: 'Ada',
    roles: ['ADMIN'],
    credential: basicCredential('ada', 'secret'),
  })
})

describe('integration queries', () => {
  it('reads the sources of the port and refuses an answer of another shape', async () => {
    server.use(http.get(`${BASE}/sources`, () => HttpResponse.json({ items: [{ code: 'cbas' }] })))

    const { result } = renderHook(() => useIntegrationSources(), { wrapper: wrapperFor() })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.error?.message).toContain('items.0.enabled')
    expect((result.current.error as { title?: string }).title).toBe(BAD_SHAPE_TITLE)
  })

  it('sends the filters of the quarantine and asks for older entries with the keyset', async () => {
    const urls: string[] = []
    server.use(
      http.get(`${BASE}/quarantine`, ({ request }) => {
        urls.push(new URL(request.url).search)
        return HttpResponse.json(
          urls.length === 1 ? { items: [ENTRY], nextBefore: 7 } : { items: [], nextBefore: null },
        )
      }),
    )

    const { result } = renderHook(() => useQuarantine({ source: 'cbas', reason: '' }), {
      wrapper: wrapperFor(),
    })
    await waitFor(() => expect(result.current.data?.pages).toHaveLength(1))
    expect(result.current.hasNextPage).toBe(true)
    await result.current.fetchNextPage()
    await waitFor(() => expect(result.current.data?.pages).toHaveLength(2))

    expect(urls[0]).toBe('?source=cbas&limit=50')
    expect(urls[1]).toBe('?source=cbas&before=7&limit=50')
    expect(result.current.hasNextPage).toBe(false)
  })

  it('looks at the list again soon while a replay in it is waiting, then settles', async () => {
    let calls = 0
    server.use(
      http.get(`${BASE}/quarantine`, () => {
        calls++
        const status = calls === 1 ? 'RECEIVED' : 'NORMALIZED'
        return HttpResponse.json({
          items: [{ ...ENTRY, replays: 1, latestReplayStatus: status }],
          nextBefore: null,
        })
      }),
    )

    const { result } = renderHook(() => useQuarantine({}), { wrapper: wrapperFor() })

    await waitFor(() =>
      expect(result.current.data?.pages[0].items[0].latestReplayStatus).toBe('RECEIVED'),
    )
    await waitFor(
      () => expect(result.current.data?.pages[0].items[0].latestReplayStatus).toBe('NORMALIZED'),
      { timeout: 4000 },
    )
    const after = calls
    await new Promise((resolve) => setTimeout(resolve, 1300))
    expect(calls).toBe(after)
  })

  it('keeps looking at a record while a replay of it is on its way', async () => {
    let calls = 0
    const attempt = (status: string, published: number) => ({
      receptionId: 'r-2',
      receivedAt: '2026-10-29T09:00:00Z',
      status,
      events: 1,
      published,
    })
    server.use(
      http.get(`${BASE}/quarantine/r-1`, () => {
        calls++
        return HttpResponse.json({
          entry: ENTRY,
          raw: {},
          rawAvailable: true,
          attempts: [calls === 1 ? attempt('RECEIVED', 0) : attempt('NORMALIZED', 1)],
        })
      }),
    )

    const { result } = renderHook(() => useQuarantineDetail('r-1'), { wrapper: wrapperFor() })

    await waitFor(() => expect(result.current.data?.attempts[0].status).toBe('RECEIVED'))
    await waitFor(() => expect(result.current.data?.attempts[0].status).toBe('NORMALIZED'), {
      timeout: 4000,
    })
    const after = calls
    await new Promise((resolve) => setTimeout(resolve, 1300))
    expect(calls).toBe(after)
  })

  it('does not ask for a record that is not chosen', () => {
    const { result } = renderHook(() => useQuarantineDetail(null), { wrapper: wrapperFor() })

    expect(result.current.fetchStatus).toBe('idle')
  })

  it('leaves out the filters that are not set, "all" included', async () => {
    const urls: string[] = []
    server.use(
      http.get(`${BASE}/mappings`, ({ request }) => {
        urls.push(new URL(request.url).search)
        return HttpResponse.json({ items: [] })
      }),
    )

    const all = renderHook(() => useIdentityMappings({ state: 'all' }), { wrapper: wrapperFor() })
    await waitFor(() => expect(all.result.current.isSuccess).toBe(true))
    const some = renderHook(
      () =>
        useIdentityMappings({ source: 'itap', entityType: 'GATE', sourceId: 'G1', state: 'open' }),
      { wrapper: wrapperFor() },
    )
    await waitFor(() => expect(some.result.current.isSuccess).toBe(true))

    expect(urls[0]).toBe('')
    expect(urls[1]).toBe('?source=itap&entityType=GATE&sourceId=G1&state=open')
  })
})

describe('integration actions', () => {
  function recorded() {
    const calls: { method: string; path: string; body: unknown }[] = []
    const note = async (request: Request) => {
      calls.push({
        method: request.method,
        path: new URL(request.url).pathname.replace('/api/v1/ports/WESTPORTS/integrations', ''),
        body: await request.json().catch(() => null),
      })
    }
    return { calls, note }
  }

  const KEY = { id: 9, prefix: 'ck_abcde', origin: 'issued', createdAt: '2026-10-29T08:00:00Z' }
  const MAPPING = {
    id: 3,
    sourceSystem: 'cbas',
    sourceEntityType: 'BERTH',
    sourceId: 'B3',
    canonicalId: 'a',
    effectiveFrom: '1970-01-01T00:00:00Z',
    createdBy: 'ada',
    createdAt: '2026-10-29T08:00:00Z',
  }

  it('replays a record by its reception id and refreshes everything about the port', async () => {
    const { calls, note } = recorded()
    server.use(
      http.post(`${BASE}/quarantine/r-1/replay`, async ({ request }) => {
        await note(request)
        return HttpResponse.json({ receptionId: 'r-2', replayOf: 'r-1' }, { status: 201 })
      }),
    )
    const client = new QueryClient()
    client.setQueryData(integrationKeys.sources('WESTPORTS'), { items: [] })
    const { result } = renderHook(() => useReplayRecord(), { wrapper: wrapperFor(client) })

    const replay = await result.current.mutateAsync('r-1')

    expect(replay).toEqual({ receptionId: 'r-2', replayOf: 'r-1' })
    expect(calls).toEqual([{ method: 'POST', path: '/quarantine/r-1/replay', body: null }])
    await waitFor(() =>
      expect(client.getQueryState(integrationKeys.sources('WESTPORTS'))?.isInvalidated).toBe(true),
    )
  })

  it('creates, rotates and revokes keys on the paths of the source', async () => {
    const { calls, note } = recorded()
    server.use(
      http.post(`${BASE}/sources/cbas/keys`, async ({ request }) => {
        await note(request)
        return HttpResponse.json({ ...KEY, key: 'ck_secret' }, { status: 201 })
      }),
      http.post(`${BASE}/sources/cbas/keys/9/rotate`, async ({ request }) => {
        await note(request)
        return HttpResponse.json({ ...KEY, id: 10, key: 'ck_secret2' }, { status: 201 })
      }),
      http.delete(`${BASE}/sources/cbas/keys/10`, async ({ request }) => {
        await note(request)
        return HttpResponse.json({ ...KEY, id: 10, revokedAt: '2026-10-29T10:00:00Z' })
      }),
    )
    const wrapper = wrapperFor()
    const create = renderHook(() => useCreateIntegrationKey(), { wrapper })
    const rotate = renderHook(() => useRotateIntegrationKey(), { wrapper })
    const revoke = renderHook(() => useRevokeIntegrationKey(), { wrapper })

    expect((await create.result.current.mutateAsync('cbas')).key).toBe('ck_secret')
    expect((await rotate.result.current.mutateAsync({ source: 'cbas', keyId: 9 })).id).toBe(10)
    expect((await revoke.result.current.mutateAsync({ source: 'cbas', keyId: 10 })).revokedAt).toBe(
      '2026-10-29T10:00:00Z',
    )

    expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual([
      'POST /sources/cbas/keys',
      'POST /sources/cbas/keys/9/rotate',
      'DELETE /sources/cbas/keys/10',
    ])
  })

  it('creates a mapping with what it was given and closes one now or at a time', async () => {
    const { calls, note } = recorded()
    server.use(
      http.post(`${BASE}/mappings`, async ({ request }) => {
        await note(request)
        return HttpResponse.json(MAPPING, { status: 201 })
      }),
      http.post(`${BASE}/mappings/3/close`, async ({ request }) => {
        await note(request)
        return HttpResponse.json({ ...MAPPING, effectiveTo: '2026-10-29T10:00:00Z' })
      }),
    )
    const wrapper = wrapperFor()
    const create = renderHook(() => useCreateIdentityMapping(), { wrapper })
    const close = renderHook(() => useCloseIdentityMapping(), { wrapper })

    await create.result.current.mutateAsync({
      sourceSystem: 'cbas',
      sourceEntityType: 'BERTH',
      sourceId: 'B3',
      canonicalId: 'a',
    })
    await close.result.current.mutateAsync({ id: 3 })
    await close.result.current.mutateAsync({ id: 3, effectiveTo: '2026-10-29T10:00:00Z' })

    expect(calls.map((c) => c.body)).toEqual([
      { sourceSystem: 'cbas', sourceEntityType: 'BERTH', sourceId: 'B3', canonicalId: 'a' },
      {},
      { effectiveTo: '2026-10-29T10:00:00Z' },
    ])
  })

  it('fails an action whose answer is not what the API promised', async () => {
    server.use(http.post(`${BASE}/quarantine/r-1/replay`, () => HttpResponse.json({ nope: 1 })))
    const { result } = renderHook(() => useReplayRecord(), { wrapper: wrapperFor() })

    await expect(result.current.mutateAsync('r-1')).rejects.toThrow(/receptionId/)
  })
})
