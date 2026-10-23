import { describe, expect, it } from 'vitest'
import { ApiError } from '@/lib/api-client'
import { createQueryClient, shouldRetry, staticQueryOptions } from './query-client'

const apiError = (status: number) => new ApiError({ status, title: `HTTP ${status}` })

describe('query client', () => {
  it('retries network, timeout and 5xx errors twice', () => {
    expect(shouldRetry(0, apiError(503))).toBe(true)
    expect(shouldRetry(1, apiError(0))).toBe(true)
    expect(shouldRetry(2, apiError(503))).toBe(false)
  })

  it('never retries a 4xx', () => {
    for (const status of [400, 401, 404, 409, 422]) {
      expect(shouldRetry(0, apiError(status))).toBe(false)
    }
  })

  it('defaults to staleTime 0, while static data stays fresh until reload', () => {
    expect(createQueryClient().getDefaultOptions().queries?.staleTime).toBe(0)
    expect(staticQueryOptions.staleTime).toBe(Infinity)
  })
})
