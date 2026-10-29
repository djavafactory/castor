import type { UseQueryResult } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { ApiError } from '@/lib/api-client'
import { ErrorCard, SkeletonRows } from './states'

/**
 * Shows the loading skeleton, the SETUP-06 error card with Retry (also when the response had the wrong shape) or the
 * data. Use it around any component that needs a query result.
 */
export function QueryBoundary<T>({
  query,
  children,
  loading = <SkeletonRows />,
}: {
  query: Pick<UseQueryResult<T>, 'data' | 'error' | 'isPending' | 'refetch'>
  children: (data: T) => ReactNode
  loading?: ReactNode
}) {
  if (query.error) {
    const error = query.error
    return (
      <ErrorCard
        title={error instanceof ApiError ? error.title : 'Something went wrong'}
        detail={error instanceof ApiError ? error.detail : error.message}
        reference={error instanceof ApiError ? error.reference : undefined}
        onRetry={() => void query.refetch()}
      />
    )
  }
  if (query.isPending || query.data === undefined) return <>{loading}</>
  return <>{children(query.data)}</>
}
