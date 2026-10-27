import type { z } from 'zod'
import { ApiError, apiRequest, type RequestOptions } from '@/lib/api-client'

export const BAD_SHAPE_TITLE = 'Unexpected response from the server'

/** The server answered, but not in the shape this version of the app knows. Retrying cannot help. */
export class ResponseShapeError extends ApiError {
  constructor(path: string, issues: string[]) {
    super({
      status: 0,
      title: BAD_SHAPE_TITLE,
      detail: `${path}: ${issues.slice(0, 3).join('; ')}`,
    })
    this.name = 'ResponseShapeError'
  }

  override get retryable(): boolean {
    return false
  }
}

/** Parses data with a schema or throws {@link ResponseShapeError}. */
export function parseResponse<S extends z.ZodType>(
  path: string,
  schema: S,
  data: unknown,
): z.infer<S> {
  const result = schema.safeParse(data)
  if (result.success) return result.data
  throw new ResponseShapeError(
    path,
    result.error.issues.map((issue) => `${issue.path.join('.') || 'body'}: ${issue.message}`),
  )
}

export type ParsedResponse<T> = { data: T; etag: string | null }

/** Request plus schema validation: the one way hooks read the API. */
export async function requestParsed<S extends z.ZodType>(
  path: string,
  schema: S,
  options?: RequestOptions,
): Promise<ParsedResponse<z.infer<S>>> {
  const response = await apiRequest<unknown>(path, options)
  return { data: parseResponse(path, schema, response.data), etag: response.etag }
}

export async function getParsed<S extends z.ZodType>(
  path: string,
  schema: S,
  options?: Omit<RequestOptions, 'method' | 'body'>,
): Promise<z.infer<S>> {
  return (await requestParsed(path, schema, { ...options, method: 'GET' })).data
}
