import { useQuery } from '@tanstack/react-query'
import { apiGet } from '@/lib/api-client'
import { getEnv } from '@/lib/env'

export interface PortInfo {
  name: string
  synthetic: boolean
}

/** The port the app shows in the header. Falls back to the configured port code until the API answers. */
export function usePort(): PortInfo {
  const code = getEnv().VITE_PORT_CODE
  const { data } = useQuery({
    queryKey: ['port', code],
    queryFn: ({ signal }) => apiGet<PortInfo>('/v1/port', { signal }).then((r) => r.data),
    retry: false,
    staleTime: 5 * 60_000,
  })
  return (
    data ?? { name: code.charAt(0).toUpperCase() + code.slice(1).toLowerCase(), synthetic: false }
  )
}
