import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { layoutQueryOptions } from '@/app/query-client'
import { fetchValidation, queryKeys, useDraftLayout, type Layout } from '@/lib/api'
import { useEditorStore } from '@/lib/stores/editor-store'

/** Changes whenever an asset of the draft is added, removed or saved, so each of those asks the server again. */
export function draftSignature(layout: Layout): string {
  let versions = 0
  for (const asset of layout.assets) versions += asset.rowVersion
  return `${layout.assets.length}:${versions}:${layout.updatedAt ?? ''}`
}

/**
 * Errors and warnings of the draft, from POST /v1/layouts/{id}/validate. The check runs again after every save (the
 * draft's signature changes when the save is read back) and waits while a save is still in flight, so it never judges
 * a half-saved draft. The previous answer stays on screen until the new one arrives.
 */
export function useDraftValidation(enabled = true) {
  const draft = useDraftLayout(undefined, enabled)
  const saving = useEditorStore((s) => s.pendingSaves.length > 0)
  const layout = draft.data
  const query = useQuery({
    queryKey: queryKeys.validation(layout?.versionId ?? '', layout ? draftSignature(layout) : ''),
    queryFn: ({ signal }) => fetchValidation(layout?.versionId ?? '', signal),
    enabled: enabled && layout !== undefined && !saving,
    placeholderData: keepPreviousData,
    ...layoutQueryOptions,
  })
  return { draft, query }
}
