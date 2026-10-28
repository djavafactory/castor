import { useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/lib/api-client'
import { defaultPortCode, queryKeys, useUpdateAsset, type Layout } from '@/lib/api'
import { useEditorStore } from '@/lib/stores/editor-store'
import type { AssetFields } from './asset-fields'
import { inverseOfChanges } from './field-values'

export const notChanged = (reason: string) => `Not changed: ${reason}`

const reasonOf = (error: ApiError) => error.errors[0]?.message ?? error.detail ?? error.title

/**
 * Saves one Inspector change: one PATCH and one undo step "<label> of <name>". The row version is read from the draft in
 * the cache, so a second change made before the first one answers still carries the right If-Match. A refusal of the
 * rules is shown with its reason and stores nothing; the returned promise tells the field whether the value was kept.
 */
export function useInspectorCommit() {
  const client = useQueryClient()
  const code = defaultPortCode()
  const { mutateAsync } = useUpdateAsset(code)

  return useCallback(
    async (
      asset: AssetFields,
      label: string,
      changes: Record<string, unknown>,
    ): Promise<boolean> => {
      const latest =
        client.getQueryData<Layout>(queryKeys.draft(code))?.assets.find((a) => a.id === asset.id) ??
        asset
      const inverse = inverseOfChanges(latest as AssetFields, changes)
      const id = asset.id
      let version = latest.rowVersion
      try {
        version = (await mutateAsync({ id, rowVersion: version, changes })).rowVersion
      } catch (error) {
        if (error instanceof ApiError && (error.status === 400 || error.status === 422)) {
          // A refusal of the rules is the editor's to fix, not a failed save to retry
          useEditorStore.getState().endSave(id)
          useEditorStore.setState((state) => ({
            failedSaves: state.failedSaves.filter((s) => s !== id),
          }))
          toast(notChanged(reasonOf(error)))
        }
        return false
      }
      useEditorStore.getState().pushHistory({
        label: `${label} of ${asset.name}`,
        undo: async () => {
          version = (await mutateAsync({ id, rowVersion: version, changes: inverse })).rowVersion
        },
        redo: async () => {
          version = (await mutateAsync({ id, rowVersion: version, changes })).rowVersion
        },
      })
      return true
    },
    [client, code, mutateAsync],
  )
}
