import { useEffect, useState } from 'react'
import { toast } from 'sonner'

export const FULLSCREEN_REFUSED = 'Fullscreen is not available in this window'

/** Enters or leaves fullscreen; a refusal (or a missing API) is a toast, never an error. */
export async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else await document.documentElement.requestFullscreen()
  } catch {
    toast(FULLSCREEN_REFUSED)
  }
}

/** True while the page is fullscreen. */
export function useFullscreen(): boolean {
  const [on, setOn] = useState(() => Boolean(document.fullscreenElement))
  useEffect(() => {
    const update = () => setOn(Boolean(document.fullscreenElement))
    document.addEventListener('fullscreenchange', update)
    return () => document.removeEventListener('fullscreenchange', update)
  }, [])
  return on
}
