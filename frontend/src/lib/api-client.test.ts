import { delay, http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '@/test/setup'
import {
  ApiError,
  apiGet,
  apiRequest,
  SESSION_ENDED_MESSAGE,
  setUnauthorizedHandler,
} from './api-client'
import { basicCredential, useAuthStore } from './stores/auth-store'

vi.mock('sonner', () => ({ toast: { error: vi.fn() } }))

const BASE = 'http://api.test/api'

beforeEach(() => {
  useAuthStore.getState().clearSession()
  setUnauthorizedHandler(null)
  vi.mocked(toast.error).mockClear()
})

describe('api client', () => {
  it('returns data and ETag, sending Accept and the Basic credential', async () => {
    useAuthStore.getState().signIn({
      username: 'ada',
      displayName: 'Ada',
      roles: ['VIEWER'],
      credential: basicCredential('ada', 'secret'),
    })
    let seen: Headers | undefined
    server.use(
      http.get(`${BASE}/v1/ports/WESTPORTS`, ({ request }) => {
        seen = request.headers
        return HttpResponse.json({ code: 'WESTPORTS' }, { headers: { ETag: '"7"' } })
      }),
    )

    const response = await apiGet<{ code: string }>('/v1/ports/WESTPORTS')

    expect(response.data).toEqual({ code: 'WESTPORTS' })
    expect(response.etag).toBe('"7"')
    expect(seen?.get('accept')).toBe('application/json')
    expect(seen?.get('authorization')).toBe(basicCredential('ada', 'secret'))
  })

  it('sends If-Match for a row version and a JSON body', async () => {
    let seen: Request | undefined
    server.use(
      http.patch(`${BASE}/v1/layouts/1/assets/2`, ({ request }) => {
        seen = request
        return new HttpResponse(null, { status: 204 })
      }),
    )

    const response = await apiRequest('/v1/layouts/1/assets/2', {
      method: 'PATCH',
      body: { name: 'Crane 1' },
      rowVersion: 3,
    })

    expect(response.status).toBe(204)
    expect(response.data).toBeUndefined()
    expect(seen?.headers.get('if-match')).toBe('"3"')
    expect(seen?.headers.get('content-type')).toBe('application/json')
  })

  it('parses Problem Details into an ApiError', async () => {
    server.use(
      http.post(`${BASE}/v1/layouts/1/publish`, () =>
        HttpResponse.json(
          {
            type: 'https://replica8.it/problems/validation',
            title: 'Layout cannot be published',
            status: 422,
            detail: '1 error',
            errors: [
              {
                code: 'OUT_OF_PORT',
                pointer: '/geometry',
                assetId: '8f14e45f-ceea-467a-9575-5d3a5c1b2c10',
                message: 'Quay 1 lies outside the port extent',
              },
            ],
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    const error = await apiRequest('/v1/layouts/1/publish', { method: 'POST' }).catch((e) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      status: 422,
      title: 'Layout cannot be published',
      detail: '1 error',
    })
    expect(error.errors[0]).toMatchObject({ code: 'OUT_OF_PORT', pointer: '/geometry' })
  })

  it('clears the session, shows a toast and routes to login on 401', async () => {
    useAuthStore.getState().signIn({
      username: 'ada',
      displayName: 'Ada',
      roles: ['VIEWER'],
      credential: basicCredential('ada', 'old'),
    })
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    server.use(
      http.get(`${BASE}/v1/ports/WESTPORTS`, () =>
        HttpResponse.json({ title: 'Unauthorized', status: 401 }, { status: 401 }),
      ),
    )

    await expect(apiGet('/v1/ports/WESTPORTS')).rejects.toMatchObject({ status: 401 })

    expect(useAuthStore.getState().credential).toBeNull()
    expect(toast.error).toHaveBeenCalledWith(SESSION_ENDED_MESSAGE)
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('leaves the session alone on 401 when handleUnauthorized is false', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    server.use(http.get(`${BASE}/v1/me`, () => new HttpResponse(null, { status: 401 })))

    await expect(apiGet('/v1/me', { handleUnauthorized: false })).rejects.toMatchObject({
      status: 401,
    })

    expect(toast.error).not.toHaveBeenCalled()
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('surfaces a 409 conflict with its Problem Details', async () => {
    server.use(
      http.patch(`${BASE}/v1/layouts/1/assets/2`, () =>
        HttpResponse.json(
          { title: 'Conflict', status: 409, detail: 'The asset was changed by someone else' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    const error = await apiRequest('/v1/layouts/1/assets/2', {
      method: 'PATCH',
      body: {},
      rowVersion: 1,
    }).catch((e) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409, title: 'Conflict' })
    expect(error.detail).toContain('changed by someone else')
  })

  it('aborts with a timeout error when the server is too slow', async () => {
    server.use(
      http.get(`${BASE}/v1/slow`, async () => {
        await delay('infinite')
        return HttpResponse.json({})
      }),
    )

    const error = await apiGet('/v1/slow', { timeoutMs: 50 }).catch((e) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error.isTimeout).toBe(true)
  })

  it('turns a network failure into an ApiError with status 0', async () => {
    server.use(http.get(`${BASE}/v1/down`, () => HttpResponse.error()))

    await expect(apiGet('/v1/down')).rejects.toMatchObject({ status: 0, title: 'Network error' })
  })
})
