/**
 * The server's clock as the browser sees it: `serverNow = serverTime + (performance.now() - receivedAt) x clockSpeed`.
 * Set from the snapshot and corrected by every heartbeat. `clockSpeed` is 1 except when the demo clock runs faster.
 */
export interface ServerClockReading {
  /** Server time (epoch ms) at the moment of the reading. */
  serverTimeMs: number
  /** `performance.now()` when the message arrived. */
  receivedAt: number
  clockSpeed: number
}

export function serverNowMs(clock: ServerClockReading, perfNow: number): number {
  return clock.serverTimeMs + (perfNow - clock.receivedAt) * clock.clockSpeed
}

/** Reads the wire `serverTime` (ISO 8601) into a clock reading; null when it is not a date. */
export function readClock(
  serverTime: string,
  clockSpeed: number,
  receivedAt: number,
): ServerClockReading | null {
  const serverTimeMs = Date.parse(serverTime)
  if (Number.isNaN(serverTimeMs)) return null
  return { serverTimeMs, receivedAt, clockSpeed }
}
