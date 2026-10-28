import { toast } from 'sonner'
import { ApiError } from '@/lib/api-client'
import { defaultPortCode, fetchDraftLayout, silently } from '@/lib/api'
import { useEditorStore, type HistoryEntry } from '@/lib/stores/editor-store'

export const cannotUndo = (name: string, user: string) =>
  `Cannot undo: ${name} was changed by ${user}.`
export const cannotRedo = (name: string, user: string) =>
  `Cannot redo: ${name} was changed by ${user}.`

/** Who changed the asset since, for the conflict toast; "another editor" when the draft cannot be read again. */
async function conflictUser(): Promise<string> {
  try {
    const draft = await fetchDraftLayout(defaultPortCode())
    return draft.updatedBy ?? 'another editor'
  } catch {
    return 'another editor'
  }
}

/**
 * Runs one undo or redo step (DESIGNER-08): the entry already moved to the other stack optimistically; a 409 (someone
 * else changed the asset) drops it instead with "Cannot undo/redo: <name> was changed by <user>", and the generic
 * conflict toast of the mutation itself stays silent so only this one shows. A step that succeeds is logged.
 */
async function run(which: 'undo' | 'redo', entry: HistoryEntry): Promise<void> {
  try {
    await silently(() => Promise.resolve(which === 'undo' ? entry.undo() : entry.redo()))
    useEditorStore.getState().logHistory(`${which === 'undo' ? 'Undo' : 'Redo'}: ${entry.label}`)
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 409) return
    const name = entry.assetName ?? entry.label
    const user = await conflictUser()
    toast(which === 'undo' ? cannotUndo(name, user) : cannotRedo(name, user))
    if (which === 'undo') useEditorStore.getState().discardFromRedo(entry)
    else useEditorStore.getState().discardFromUndo(entry)
  }
}

export function runUndo(): void {
  const entry = useEditorStore.getState().undo()
  if (entry) void run('undo', entry)
}

export function runRedo(): void {
  const entry = useEditorStore.getState().redo()
  if (entry) void run('redo', entry)
}
