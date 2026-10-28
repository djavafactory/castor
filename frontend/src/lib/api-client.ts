import { toast } from 'sonner'
import type { components } from '@/types/api'
import { getEnv } from './env'
import { useAuthStore } from './stores/auth-store'

export type ProblemDetails = components['schemas']['ProblemDetails']
export type ApiErrorItem = components['schemas']['ProblemError']

export const DEFAULT_TIMEOUT_MS = 15_000
export const SESSION_ENDED_MESSAGE = 'Session ended, please sign in again'

/** RFC 9457 Problem Details as thrown by every failed request. */
export class ApiError extends Error {
  readonly status: number
  readonly title: string
  readonly detail: string | undefined
  readonly errors: ApiErrorItem[]

  constructor(init: { status: number; title: string; detail?: string; errors?: ApiErrorItem[] }) {
    super(init.detail ?? init.title)
    this.name = 'ApiError'
    this.status = init.status
    this.title = init.title
    this.detail = init.detail
    this.errors = init.errors ?? []
  }

  /** False when asking again cannot help: a 4xx (the request is wrong) or a response of an unknown shape. */
  get retryable(): boolean {
    return this.status < 400 || this.status >= 500
  }

  get isTimeout(): boolean {
    return this.status === 0 && this.title === 'Request timed out'
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  /** JSON-serialisable request body, or a `FormData` (a file upload) which is sent as it is. */
  body?: unknown
  query?: Record<string, string | number | boolean | undefined>
  /** Sent as `If-Match` (quoted ETag form). Required by the API for PATCH and DELETE. */
  rowVersion?: number | string
  headers?: Record<string, string>
  signal?: AbortSignal
  timeoutMs?: number
  /** Set to false for the login call, where a 401 means bad credentials, not an ended session. */
  handleUnauthorized?: boolean
}

export interface ApiResponse<T> {
  data: T
  /** `ETag` response header (the row version), or null when absent. */
  etag: string | null
  status: number
  /** File name of a download, from `Content-Disposition`. */
  filename?: string | null
}

type UnauthorizedHandler = () => void

let onUnauthorized: UnauthorizedHandler | null = null

/** The router registers how to leave the current page for /login. */
export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  onUnauthorized = handler
}

function buildUrl(path: string, query: RequestOptions['query']): string {
  const base = getEnv().VITE_API_URL.replace(/\/+$/, '')
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value !== undefined) search.set(key, String(value))
  }
  const qs = search.toString()
  return `${base}${path.startsWith('/') ? path : `/${path}`}${qs ? `?${qs}` : ''}`
}

function ifMatch(rowVersion: number | string): string {
  const value = String(rowVersion)
  return value.startsWith('"') || value.startsWith('W/') ? value : `"${value}"`
}

/** The file name of `Content-Disposition: attachment; filename="..."`, or null. */
function filenameOf(response: Response): string | null {
  const match = /filename="?([^";]+)"?/i.exec(response.headers.get('Content-Disposition') ?? '')
  return match?.[1] ?? null
}

function isJson(response: Response): boolean {
  return /json/i.test(response.headers.get('content-type') ?? '')
}

async function toApiError(response: Response): Promise<ApiError> {
  let problem: Partial<ProblemDetails> = {}
  if (isJson(response)) {
    try {
      problem = (await response.json()) as Partial<ProblemDetails>
    } catch {
      // Fall through to the generic error below.
    }
  }
  return new ApiError({
    status: problem.status ?? response.status,
    title: problem.title ?? (response.statusText || `HTTP ${response.status}`),
    detail: problem.detail,
    errors: Array.isArray(problem.errors) ? problem.errors : [],
  })
}

function endSession(): void {
  useAuthStore.getState().clearSession()
  toast.error(SESSION_ENDED_MESSAGE)
  onUnauthorized?.()
}

/** The only place that calls `fetch`. */
export async function apiRequest<T>(
  path: string,
  options: RequestOptions = {},
): Promise<ApiResponse<T>> {
  const {
    method = 'GET',
    body,
    query,
    rowVersion,
    headers,
    signal,
    handleUnauthorized = true,
  } = options
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS

  const requestHeaders: Record<string, string> = { Accept: 'application/json', ...headers }
  const credential = useAuthStore.getState().credential
  if (credential) requestHeaders.Authorization = credential
  const upload = typeof FormData !== 'undefined' && body instanceof FormData
  // a file upload sets its own Content-Type, with the multipart boundary
  if (body !== undefined && !upload) requestHeaders['Content-Type'] = 'application/json'
  if (rowVersion !== undefined) requestHeaders['If-Match'] = ifMatch(rowVersion)

  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
  const onCallerAbort = () => controller.abort()
  if (signal?.aborted) controller.abort()
  else signal?.addEventListener('abort', onCallerAbort, { once: true })

  let response: Response
  try {
    response = await fetch(buildUrl(path, query), {
      method,
      headers: requestHeaders,
      body: body === undefined ? undefined : upload ? body : JSON.stringify(body),
      signal: controller.signal,
    })

    if (!response.ok) {
      if (response.status === 401 && handleUnauthorized) endSession()
      throw await toApiError(response)
    }

    const etag = response.headers.get('ETag')
    const hasBody = response.status !== 204 && response.status !== 205
    const data = (hasBody && isJson(response) ? await response.json() : undefined) as T
    return { data, etag, status: response.status, filename: filenameOf(response) }
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (timedOut) {
      throw new ApiError({
        status: 0,
        title: 'Request timed out',
        detail: `The server did not answer within ${Math.round(timeoutMs / 1000)} s.`,
      })
    }
    if (signal?.aborted) throw error
    throw new ApiError({
      status: 0,
      title: 'Network error',
      detail: error instanceof Error ? error.message : 'The request could not be sent.',
    })
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', onCallerAbort)
  }
}

export const apiGet = <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) =>
  apiRequest<T>(path, { ...options, method: 'GET' })
