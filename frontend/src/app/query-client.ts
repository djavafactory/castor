import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/lib/api-client'

/** Never retry a 4xx (the request is wrong or forbidden); retry network, timeout and 5xx twice. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false
  return failureCount < 2
}

/**
 * Static data (port, catalogue, asset types, reference features): fetched once, kept until
 * the page is reloaded. Spread into `useQuery` options.
 */
export const staticQueryOptions = {
  staleTime: Infinity,
  gcTime: Infinity,
} as const

/** Layout data always refetches when it is used again. */
export const layoutQueryOptions = { staleTime: 0 } as const

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 0,
        retry: shouldRetry,
        refetchOnWindowFocus: false,
      },
    },
  })
}

export const queryClient = createQueryClient()
