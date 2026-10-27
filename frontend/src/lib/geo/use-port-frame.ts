import { usePort } from '@/lib/api'
import { getEnv } from '@/lib/env'
import { setActiveFrame, type QuayFrameParams } from '.'

/** The part of GET /api/v1/ports/{code} the geo module needs. */
export interface PortFrameResponse {
  origin: { lon: number; lat: number }
  frameRotDeg: number
}

export function toFrameParams(port: PortFrameResponse): QuayFrameParams {
  return { lon0: port.origin.lon, lat0: port.origin.lat, rotDeg: port.frameRotDeg }
}

/**
 * Loads the quay frame of the configured port and activates it for lib/geo. Returns true once layers may
 * convert positions; render 2D and 3D layers only then.
 */
export function usePortFrame(): boolean {
  const { data } = usePort(getEnv().VITE_PORT_CODE)
  if (!data) return false
  setActiveFrame(toFrameParams(data))
  return true
}
