import { useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { defaultPortCode, queryKeys } from '@/lib/api'
import { useEditorStore } from '@/lib/stores/editor-store'

/** Resolves once no save is in flight. */
function waitForSaves(): Promise<void> {
  return new Promise((resolve) => {
    if (useEditorStore.getState().pendingSaves.length === 0) {
      resolve()
      return
    }
    const unsubscribe = useEditorStore.subscribe((state) => {
      if (state.pendingSaves.length === 0) {
        unsubscribe()
        resolve()
      }
    })
  })
}

/** The message after a check: what was saved and how far coordinates moved, or what is still not saved. */
export function checkMessage(failed: number, largestAdjustmentMm: number): string {
  if (failed > 0) {
    return `${failed} ${failed === 1 ? 'change is' : 'changes are'} not saved yet. Retry them from the asset, or reload the draft.`
  }
  return `All changes saved. Coordinates stored as WGS84 to 7 decimals (largest adjustment ${largestAdjustmentMm.toFixed(1)} mm).`
}

/**
 * "Check and save" (Ctrl+S): waits for the saves in flight, reads the draft again and re-validates it, then says what
 * is saved. Nothing is sent that a field has not already sent: the check only confirms. The state lives in the editor
 * store, so the button and Ctrl+S show the same message wherever the right panel stands.
 */
export function useCheckAndSave() {
  const client = useQueryClient()
  const code = defaultPortCode()
  const busy = useEditorStore((s) => s.checking)
  const message = useEditorStore((s) => s.checkMessage)

  const run = useCallback(async () => {
    const { setChecking } = useEditorStore.getState()
    setChecking(true, null)
    await waitForSaves()
    await client.refetchQueries({ queryKey: queryKeys.draft(code), exact: true })
    await client.refetchQueries({ queryKey: ['validation'] })
    const editor = useEditorStore.getState()
    setChecking(false, checkMessage(editor.failedSaves.length, editor.largestAdjustmentMm))
  }, [client, code])

  return { run, busy, message }
}
