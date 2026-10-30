import { useEffect } from 'react'
import { defaultPortCode } from '@/lib/api'
import { getEnv } from '@/lib/env'
import { useAuthStore } from '@/lib/stores/auth-store'
import { startLiveStream, stopLiveStream } from './live-stream'

/**
 * Monitor holds the live state of its port: the stream starts when this mounts with a signed-in user and stops when
 * Monitor is left, the user signs out or the page goes. Renders nothing.
 */
export function LiveStreamRunner() {
  const signedIn = useAuthStore((s) => s.credential !== null)
  useEffect(() => {
    if (!signedIn) return
    startLiveStream(defaultPortCode(), getEnv().VITE_API_URL)
    return stopLiveStream
  }, [signedIn])
  return null
}
