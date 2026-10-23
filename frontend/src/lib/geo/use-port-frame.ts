import { useQuery } from '@tanstack/react-query'
import { apiGet } from '@/lib/api-client'
import { getEnv } from '@/lib/env'
import { setActiveFrame, type QuayFrameParams } from '.'

/** The part of GET /api/v1/ports/{code} the geo module needs. */
export interface PortFrameResponse {
  origin: { lon: number; lat: number }
  rotDeg: number
}

export function toFrameParams(port: PortFrameResponse): QuayFrameParams {
  return { lon0: port.origin.lon, lat0: port.origin.lat, rotDeg: port.rotDeg }
}

/**
 * Loads the quay frame of the configured port and activates it for lib/geo. Returns true once layers may
 * convert positions; render 2D and 3D layers only then.
 */
export function usePortFrame(): boolean {
  const code = getEnv().VITE_PORT_CODE
  const { data } = useQuery({
    queryKey: ['port-frame', code],
    queryFn: ({ signal }) =>
      apiGet<PortFrameResponse>(`/v1/ports/${code}`, { signal }).then((r) => r.data),
    retry: false,
    staleTime: Infinity,
  })
  if (!data) return false
  setActiveFrame(toFrameParams(data))
  return true
}
