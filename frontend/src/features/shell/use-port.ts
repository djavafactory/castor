import { usePort as usePortRecord } from '@/lib/api'
import { getEnv } from '@/lib/env'

export interface PortInfo {
  name: string
  synthetic: boolean
}

/** The port the app shows in the header. Falls back to the configured port code until the API answers. */
export function usePort(): PortInfo {
  const code = getEnv().VITE_PORT_CODE
  const { data } = usePortRecord(code)
  return (
    data ?? { name: code.charAt(0).toUpperCase() + code.slice(1).toLowerCase(), synthetic: false }
  )
}
