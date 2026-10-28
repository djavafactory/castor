import type { QueryClient } from '@tanstack/react-query'
import { apiRequest } from '@/lib/api-client'
import {
  defaultPortCode,
  fetchDraftLayout,
  importGeoJson,
  queryKeys,
  type ImportReport,
  type Layout,
  type LayoutAsset,
} from '@/lib/api'
import { useEditorStore } from '@/lib/stores/editor-store'
import { importedHistory } from './exchange-text'

/** What an import did to the draft, found by comparing the draft before and after it. */
type Effect = { added: string[]; updated: LayoutAsset[] }

/** Fields a patch must not send back: identity, what the server computes and what cannot change. */
const NOT_RESTORED = new Set([
  'id',
  'typeCode',
  'type',
  'code',
  'rowVersion',
  'computed',
  'override',
  'modelRev',
])

const draftNow = (client: QueryClient, code: string): Promise<Layout> =>
  client.fetchQuery({
    queryKey: queryKeys.draft(code),
    queryFn: ({ signal }) => fetchDraftLayout(code, signal),
    staleTime: 0,
  })

function effectOf(before: Layout, after: Layout): Effect {
  const was = new Map(before.assets.map((a) => [a.id, a]))
  return {
    added: after.assets.filter((a) => !was.has(a.id)).map((a) => a.id),
    updated: after.assets.flatMap((a) => {
      const previous = was.get(a.id)
      return previous !== undefined && previous.rowVersion !== a.rowVersion ? [previous] : []
    }),
  }
}

const restorable = (asset: LayoutAsset): Record<string, unknown> =>
  Object.fromEntries(Object.entries(asset).filter(([key]) => !NOT_RESTORED.has(key)))

/** Undo of an import: the added assets go, the updated ones get their earlier values back. */
async function revert(client: QueryClient, code: string, effect: Effect): Promise<void> {
  const draft = await draftNow(client, code)
  const path = (id: string) =>
    `/v1/layouts/${encodeURIComponent(draft.versionId)}/assets/${encodeURIComponent(id)}`
  try {
    for (const id of effect.added) {
      const current = draft.assets.find((a) => a.id === id)
      if (current) await apiRequest(path(id), { method: 'DELETE', rowVersion: current.rowVersion })
    }
    for (const previous of effect.updated) {
      const current = draft.assets.find((a) => a.id === previous.id)
      if (current) {
        await apiRequest(path(previous.id), {
          method: 'PATCH',
          rowVersion: current.rowVersion,
          body: restorable(previous),
        })
      }
    }
  } finally {
    void client.invalidateQueries({ queryKey: queryKeys.draft(code) })
    void client.invalidateQueries({ queryKey: ['validation'] })
  }
}

/**
 * Stores the file in the draft and records it as one undo step (DESIGNER-08): undo takes the imported assets out again
 * and restores the updated ones, redo sends the same file once more. Returns the server's report.
 */
export async function importIntoDraft(
  client: QueryClient,
  layoutId: string,
  file: File,
): Promise<ImportReport> {
  const code = defaultPortCode()
  const run = async (): Promise<{ report: ImportReport; effect: Effect }> => {
    const before = await draftNow(client, code)
    const report = await importGeoJson(layoutId, file, false)
    const after = await draftNow(client, code)
    void client.invalidateQueries({ queryKey: ['validation'] })
    return { report, effect: effectOf(before, after) }
  }

  const first = await run()
  let effect = first.effect
  const count = first.report.added + first.report.updated
  if (count > 0) {
    useEditorStore.getState().pushHistory({
      label: importedHistory(count, file.name),
      undo: () => revert(client, code, effect),
      redo: async () => {
        effect = (await run()).effect
      },
    })
  }
  return first.report
}
