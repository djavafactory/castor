import { toast } from 'sonner'
import { ApiError } from '@/lib/api-client'
import { exportGeoJson } from '@/lib/api'
import { saveTextFile } from '@/lib/download'
import { useEditorStore } from '@/lib/stores/editor-store'
import { exportedMessage } from './exchange-text'

/** What the server says about a refused request, else a plain message. */
export const problemText = (error: unknown): string =>
  error instanceof ApiError ? (error.detail ?? error.title) : 'Something went wrong. Try again.'

/** Downloads a version as GeoJSON and notes it in the edit history (DESIGNER-10). */
export async function downloadExport(layoutId: string): Promise<void> {
  try {
    const file = await exportGeoJson(layoutId)
    saveTextFile(file.filename, file.text)
    useEditorStore.getState().logHistory(exportedMessage(file.featureCount))
  } catch (error) {
    toast.error(problemText(error))
  }
}
