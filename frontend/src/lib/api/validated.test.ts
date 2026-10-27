import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { z } from 'zod'
import { server } from '@/test/setup'
import { getParsed, parseResponse, requestParsed, ResponseShapeError } from './validated'

const BASE = 'http://api.test/api'
const schema = z.object({ n: z.number() })

describe('parseResponse', () => {
  it('returns parsed data', () => {
    expect(parseResponse('/x', schema, { n: 1, extra: true })).toEqual({ n: 1 })
  })

  it('names the path and the first failing fields', () => {
    expect.assertions(4)
    try {
      parseResponse('/x', z.object({ a: z.number(), b: z.string() }), {})
    } catch (error) {
      expect(error).toBeInstanceOf(ResponseShapeError)
      const shape = error as ResponseShapeError
      expect(shape.detail).toContain('/x')
      expect(shape.detail).toContain('a:')
      expect(shape.retryable).toBe(false)
    }
  })

  it('describes a body that is not an object', () => {
    expect(() => parseResponse('/y', schema, null)).toThrow(/body:/)
  })
})

describe('requestParsed and getParsed', () => {
  it('returns data and the ETag', async () => {
    server.use(
      http.get(`${BASE}/v1/thing`, () =>
        HttpResponse.json({ n: 2 }, { headers: { ETag: '"abc"' } }),
      ),
    )
    expect(await requestParsed('/v1/thing', schema)).toEqual({ data: { n: 2 }, etag: '"abc"' })
    expect(await getParsed('/v1/thing', schema)).toEqual({ n: 2 })
  })

  it('throws a shape error for a wrong answer', async () => {
    server.use(http.get(`${BASE}/v1/thing`, () => HttpResponse.json({ n: 'two' })))
    await expect(getParsed('/v1/thing', schema)).rejects.toBeInstanceOf(ResponseShapeError)
  })
})
