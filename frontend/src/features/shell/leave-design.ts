import { toast } from 'sonner'
import { useEditorStore } from '@/lib/stores/editor-store'

export const SAVING_MESSAGE = 'Saving changes...'
export const SAVE_FAILED_PROMPT = 'Some changes could not be saved. Leave Design anyway?'
export const MONITOR_RELOADED_MESSAGE = 'Monitor: showing the latest published layout'
export const NOT_AN_EDITOR_MESSAGE =
  'Design mode is for layout editors. Sign in with an editor account.'

const SAVE_WAIT_MS = 10_000

export function waitForSaves(timeoutMs: number): Promise<void> {
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(timer)
      unsubscribe()
      resolve()
    }
    const unsubscribe = useEditorStore.subscribe((state) => {
      if (state.pendingSaves.length === 0) done()
    })
    const timer = setTimeout(done, timeoutMs)
    if (useEditorStore.getState().pendingSaves.length === 0) done()
  })
}

/**
 * Leaving Design never asks "discard changes?" because every edit is already saved to the server draft.
 * Pending saves are awaited; a failed (or still unfinished) save asks once before leaving.
 */
export async function confirmLeaveDesign(
  options: { confirm?: (message: string) => boolean; timeoutMs?: number } = {},
): Promise<boolean> {
  const { confirm = (message: string) => window.confirm(message), timeoutMs = SAVE_WAIT_MS } =
    options
  if (useEditorStore.getState().pendingSaves.length > 0) {
    const id = toast.loading(SAVING_MESSAGE)
    await waitForSaves(timeoutMs)
    toast.dismiss(id)
  }
  const { pendingSaves, failedSaves } = useEditorStore.getState()
  if (pendingSaves.length > 0 || failedSaves.length > 0) return confirm(SAVE_FAILED_PROMPT)
  return true
}
