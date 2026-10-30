import { useEffect, useState } from 'react'
import { serverNow } from '@/features/live'

const TICK_MS = 30_000

/** The time ages are counted against: the server clock when the live stream has one, else this browser's. */
export function currentMs(): number {
  return serverNow() ?? Date.now()
}

/** The current time, refreshed every half minute so an age on the screen does not stand still. */
export function useNowMs(): number {
  const [now, setNow] = useState(currentMs)
  useEffect(() => {
    const timer = setInterval(() => setNow(currentMs()), TICK_MS)
    return () => clearInterval(timer)
  }, [])
  return now
}
