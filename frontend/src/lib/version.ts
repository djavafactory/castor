import { useQuery } from '@tanstack/react-query'
import { apiGet } from '@/lib/api-client'
import type { components } from '@/types/api'

export type ServerVersion = components['schemas']['Version']

const SHOW_DETAILS_IN = ['local', 'dev']

/** GET /v1/version needs no login, so the login page can call it. */
export function fetchServerVersion(signal?: AbortSignal): Promise<ServerVersion> {
  return apiGet<ServerVersion>('/v1/version', { handleUnauthorized: false, signal }).then(
    (r) => r.data,
  )
}

/** The server version, or undefined while loading or when the server does not answer. */
export function useServerVersion(enabled = true): string | undefined {
  const query = useQuery({
    queryKey: ['server-version'],
    queryFn: ({ signal }) => fetchServerVersion(signal),
    enabled,
    retry: false,
    staleTime: Infinity,
  })
  return query.data?.version
}

/** Semantic versioning ignores build metadata (+commit), so two builds of one version still match. */
export function sameVersion(a: string, b: string): boolean {
  return a.split('+')[0] === b.split('+')[0]
}

/**
 * "Castor v<version>", plus commit and environment in local and dev, plus "(server v<x>)" when the
 * server runs another version than this frontend.
 */
export function versionLine(appEnv: string, serverVersion?: string): string {
  let line = `Castor v${__APP_VERSION__}`
  if (SHOW_DETAILS_IN.includes(appEnv)) line += ` · ${__GIT_COMMIT__} · ${appEnv}`
  if (serverVersion && !sameVersion(serverVersion, __APP_VERSION__)) {
    line += ` (server v${serverVersion})`
  }
  return line
}
