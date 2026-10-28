import { useMutation, useQueryClient, type QueryClient, type QueryKey } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ApiError, apiRequest } from '@/lib/api-client'
import { useEditorStore } from '@/lib/stores/editor-store'
import { defaultPortCode, fetchDraftLayout, queryKeys } from './hooks'
import {
  savedAssetSchema,
  layoutDocumentSchema,
  layoutSchema,
  type Layout,
  type LayoutAsset,
} from './schemas'
import { parseResponse } from './validated'

/**
 * Mutation hooks for the designer (DESIGNER-04) and publishing (PUB). Each one updates the cached draft at once
 * (optimistic), puts the previous value back when the server refuses and refetches when it settles.
 * The endpoints are fixed by DESIGNER-04 and PUB.
 */

type Snapshot = { key: QueryKey; previous: Layout | undefined; changed: boolean }

/** The id of the draft of a port: from the cache, else read from the server. */
async function draftLayoutId(client: QueryClient, code: string): Promise<string> {
  const cached = client.getQueryData<Layout>(queryKeys.draft(code))
  if (cached) return cached.versionId
  const draft = await client.fetchQuery({
    queryKey: queryKeys.draft(code),
    queryFn: ({ signal }) => fetchDraftLayout(code, signal),
  })
  return draft.versionId
}

export const conflictMessage = (user: string) =>
  `This asset was changed by ${user}. Reloaded the latest version.`

/** Another editor saved first: read the draft again and say who changed it. Not a failed save to retry. */
async function reloadAfterConflict(client: QueryClient, code: string): Promise<void> {
  let user = 'another editor'
  try {
    const draft = await client.fetchQuery({
      queryKey: queryKeys.draft(code),
      queryFn: ({ signal }) => fetchDraftLayout(code, signal),
      staleTime: 0,
    })
    user = draft.updatedBy ?? user
  } catch {
    // The reload failed too; the message still tells the editor to look again.
  }
  toast(conflictMessage(user))
}

/** A save that ended in error: a conflict reloads the draft, anything else waits for Retry. */
function saveFailed(
  client: QueryClient,
  code: string,
  saveId: string,
  error: unknown,
  retry: () => void,
): void {
  const editor = useEditorStore.getState()
  if (error instanceof ApiError && error.status === 409) {
    editor.endSave(saveId)
    void reloadAfterConflict(client, code)
    return
  }
  editor.failSave(saveId, retry)
}

/** Applies a change to the cached layout and returns what is needed to undo it. */
async function applyOptimistic(
  client: QueryClient,
  key: QueryKey,
  change: (layout: Layout) => Layout,
): Promise<Snapshot> {
  await client.cancelQueries({ queryKey: key })
  const previous = client.getQueryData<Layout>(key)
  if (previous) client.setQueryData<Layout>(key, change(previous))
  return { key, previous, changed: previous !== undefined }
}

function rollback(client: QueryClient, snapshot: Snapshot | undefined): void {
  if (!snapshot?.changed) return
  if (snapshot.previous === undefined) client.removeQueries({ queryKey: snapshot.key, exact: true })
  else client.setQueryData(snapshot.key, snapshot.previous)
}

export type NewAsset = { typeCode: string } & Record<string, unknown>

/** The server names the type `type`; an id in the body re-creates a deleted asset (undo). */
function toWire({ typeCode, ...rest }: NewAsset): Record<string, unknown> {
  return { type: typeCode, ...rest }
}
export type AssetPatch = { id: string; rowVersion: number; changes: Record<string, unknown> }

/** Id given to an asset until the server has answered. */
export const TEMP_ID_PREFIX = 'pending-'

export function useCreateAsset(code: string = defaultPortCode()) {
  const client = useQueryClient()
  const key = queryKeys.draft(code)
  const mutation = useMutation({
    mutationFn: async (asset: NewAsset): Promise<LayoutAsset> => {
      const layoutId = await draftLayoutId(client, code)
      const response = await apiRequest<unknown>(
        `/v1/layouts/${encodeURIComponent(layoutId)}/assets`,
        {
          method: 'POST',
          body: toWire(asset),
        },
      )
      return parseResponse('POST assets', savedAssetSchema, response.data).asset
    },
    onMutate: (asset) => {
      const id = `${TEMP_ID_PREFIX}${crypto.randomUUID()}`
      useEditorStore.getState().beginSave(id)
      return applyOptimistic(client, key, (layout) => ({
        ...layout,
        assets: [...layout.assets, { ...asset, id, rowVersion: 0 }],
      })).then((snapshot) => ({ ...snapshot, saveId: id }))
    },
    onSuccess: (_asset, _vars, context) => useEditorStore.getState().endSave(context.saveId),
    onError: (error, asset, context) => {
      rollback(client, context)
      if (context) saveFailed(client, code, context.saveId, error, () => mutation.mutate(asset))
    },
    onSettled: () => client.invalidateQueries({ queryKey: key }),
  })
  return mutation
}

export function useUpdateAsset(code: string = defaultPortCode()) {
  const client = useQueryClient()
  const key = queryKeys.draft(code)
  const mutation = useMutation({
    mutationFn: async ({ id, rowVersion, changes }: AssetPatch): Promise<LayoutAsset> => {
      const layoutId = await draftLayoutId(client, code)
      const response = await apiRequest<unknown>(
        `/v1/layouts/${encodeURIComponent(layoutId)}/assets/${encodeURIComponent(id)}`,
        {
          method: 'PATCH',
          body: changes,
          rowVersion,
        },
      )
      return parseResponse('PATCH asset', savedAssetSchema, response.data).asset
    },
    onMutate: ({ id, changes }) => {
      useEditorStore.getState().beginSave(id)
      return applyOptimistic(client, key, (layout) => ({
        ...layout,
        assets: layout.assets.map((asset) => (asset.id === id ? { ...asset, ...changes } : asset)),
      }))
    },
    onSuccess: (_asset, { id }) => useEditorStore.getState().endSave(id),
    onError: (error, patch, snapshot) => {
      rollback(client, snapshot)
      saveFailed(client, code, patch.id, error, () => mutation.mutate(patch))
    },
    onSettled: () => client.invalidateQueries({ queryKey: key }),
  })
  return mutation
}

export function useDeleteAsset(code: string = defaultPortCode()) {
  const client = useQueryClient()
  const key = queryKeys.draft(code)
  const mutation = useMutation({
    mutationFn: async ({ id, rowVersion }: { id: string; rowVersion: number }): Promise<void> => {
      const layoutId = await draftLayoutId(client, code)
      await apiRequest<unknown>(
        `/v1/layouts/${encodeURIComponent(layoutId)}/assets/${encodeURIComponent(id)}`,
        {
          method: 'DELETE',
          rowVersion,
        },
      )
    },
    onMutate: ({ id }) => {
      useEditorStore.getState().beginSave(id)
      return applyOptimistic(client, key, (layout) => ({
        ...layout,
        assets: layout.assets.filter((asset) => asset.id !== id),
      }))
    },
    onSuccess: (_void, { id }) => useEditorStore.getState().endSave(id),
    onError: (error, target, snapshot) => {
      rollback(client, snapshot)
      saveFailed(client, code, target.id, error, () => mutation.mutate(target))
    },
    onSettled: () => client.invalidateQueries({ queryKey: key }),
  })
  return mutation
}

export function useDuplicateAsset(code: string = defaultPortCode()) {
  const client = useQueryClient()
  const key = queryKeys.draft(code)
  const mutation = useMutation({
    mutationFn: async (assetId: string): Promise<LayoutAsset> => {
      const layoutId = await draftLayoutId(client, code)
      const response = await apiRequest<unknown>(
        `/v1/layouts/${encodeURIComponent(layoutId)}/assets/${encodeURIComponent(assetId)}/duplicate`,
        { method: 'POST' },
      )
      return parseResponse('POST duplicate', savedAssetSchema, response.data).asset
    },
    onMutate: (assetId) => {
      useEditorStore.getState().beginSave(assetId)
      return applyOptimistic(client, key, (layout) => {
        const original = layout.assets.find((a) => a.id === assetId)
        if (!original) return layout
        return {
          ...layout,
          assets: [
            ...layout.assets,
            { ...original, id: `${TEMP_ID_PREFIX}${crypto.randomUUID()}`, rowVersion: 0 },
          ],
        }
      })
    },
    onSuccess: (_asset, assetId) => useEditorStore.getState().endSave(assetId),
    onError: (error, assetId, snapshot) => {
      rollback(client, snapshot)
      saveFailed(client, code, assetId, error, () => mutation.mutate(assetId))
    },
    onSettled: () => client.invalidateQueries({ queryKey: key }),
  })
  return mutation
}

export type DraftBase = 'published' | 'blank' | (string & {})

/** Starts the draft of the port: from the published layout, an older version (its id) or nothing. */
export function useCreateDraft(code: string = defaultPortCode()) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (basedOn: DraftBase) => createDraft(code, basedOn),
    onSuccess: (draft) => {
      client.setQueryData<Layout>(queryKeys.draft(code), draft)
      return client.invalidateQueries({ queryKey: queryKeys.versions(code) })
    },
  })
}

export async function createDraft(code: string, basedOn: DraftBase): Promise<Layout> {
  const response = await apiRequest<unknown>(`/v1/ports/${encodeURIComponent(code)}/layouts`, {
    method: 'POST',
    body: { basedOn },
  })
  return parseResponse('POST layouts', layoutDocumentSchema, response.data)
}

/** Throws the draft away (PUBLISHER). The next entry to Design starts a new one from the published layout. */
export function useDiscardDraft(code: string = defaultPortCode()) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (layoutId: string): Promise<void> => {
      await apiRequest<unknown>(`/v1/layouts/${encodeURIComponent(layoutId)}`, {
        method: 'DELETE',
      })
    },
    onSuccess: () => {
      client.removeQueries({ queryKey: queryKeys.draft(code), exact: true })
      return client.invalidateQueries({ queryKey: queryKeys.versions(code) })
    },
  })
}

/** Publishes the draft: the draft becomes the published layout in the cache and goes away as a draft. */
export function usePublishLayout(code: string = defaultPortCode()) {
  const client = useQueryClient()
  const draftKey = queryKeys.draft(code)
  const publishedKey = queryKeys.published(code)
  return useMutation({
    mutationFn: async (): Promise<Layout> => {
      const response = await apiRequest<unknown>(
        `/v1/ports/${encodeURIComponent(code)}/layout/publish`,
        { method: 'POST' },
      )
      return parseResponse('POST publish', layoutSchema, response.data)
    },
    onMutate: async () => {
      const draft = await applyOptimistic(client, draftKey, (layout) => layout)
      const published = await applyOptimistic(client, publishedKey, (layout) => layout)
      if (draft.previous) {
        client.setQueryData<Layout>(publishedKey, { ...draft.previous, status: 'PUBLISHED' })
        published.changed = true
        client.removeQueries({ queryKey: draftKey, exact: true })
      }
      return { draft, published }
    },
    onError: (_error, _vars, context) => {
      if (!context) return
      rollback(client, context.draft)
      rollback(client, context.published)
    },
    onSettled: () =>
      Promise.all([
        client.invalidateQueries({ queryKey: publishedKey }),
        client.invalidateQueries({ queryKey: queryKeys.current(code) }),
        client.invalidateQueries({ queryKey: draftKey }),
        client.invalidateQueries({ queryKey: queryKeys.versions(code) }),
        client.invalidateQueries({ queryKey: queryKeys.port(code) }),
      ]),
  })
}
