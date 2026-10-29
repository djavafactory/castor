import { useMutation, useQueryClient, type QueryClient, type QueryKey } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ApiError, apiRequest } from '@/lib/api-client'
import { useEditorStore } from '@/lib/stores/editor-store'
import { defaultPortCode, fetchDraftLayout, queryKeys } from './hooks'
import {
  savedAssetSchema,
  layoutDocumentSchema,
  publishResultSchema,
  type Layout,
  type LayoutAsset,
  type PublishResult,
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

/** Keeps the largest rounding change of the session for "Check and save"; returns the stored asset. */
function noteSaved(saved: { asset: LayoutAsset; adjustmentMm?: number }): LayoutAsset {
  useEditorStore.getState().noteAdjustment(saved.adjustmentMm ?? 0)
  return saved.asset
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

/** How many undo/redo steps (DESIGNER-08) are in flight; while positive, a 409 shows no toast of its own, since the
 *  caller is about to show "Cannot undo/redo: <name> was changed by <user>" instead. */
let silentConflicts = 0

/** Runs `fn` without the generic conflict toast of a 409 it causes (DESIGNER-08 undo and redo). */
export async function silently<T>(fn: () => Promise<T>): Promise<T> {
  silentConflicts++
  try {
    return await fn()
  } finally {
    silentConflicts--
  }
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
    if (silentConflicts === 0) void reloadAfterConflict(client, code)
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
      return noteSaved(parseResponse('POST assets', savedAssetSchema, response.data))
    },
    onMutate: (asset) => {
      const id = `${TEMP_ID_PREFIX}${crypto.randomUUID()}`
      useEditorStore.getState().beginSave(id)
      return applyOptimistic(client, key, (layout) => ({
        ...layout,
        assets: [...layout.assets, { links: [], ...asset, id, rowVersion: 0 }],
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
      return noteSaved(parseResponse('PATCH asset', savedAssetSchema, response.data))
    },
    onMutate: ({ id, changes }) => {
      useEditorStore.getState().beginSave(id)
      return applyOptimistic(client, key, (layout) => ({
        ...layout,
        assets: layout.assets.map((asset) => (asset.id === id ? { ...asset, ...changes } : asset)),
      }))
    },
    onSuccess: (saved, { id }) => {
      useEditorStore.getState().endSave(id)
      // the server's copy carries the new row version: the next change must send it, not wait for the refetch
      client.setQueryData<Layout>(
        key,
        (layout) =>
          layout && {
            ...layout,
            assets: layout.assets.map((asset) =>
              asset.id === id ? { ...asset, ...saved } : asset,
            ),
          },
      )
    },
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
      return noteSaved(parseResponse('POST duplicate', savedAssetSchema, response.data))
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

/** Everything a new published version changes: what Monitor shows, the port's pointer and the version list. */
function invalidatePublished(client: QueryClient, code: string): Promise<unknown> {
  return Promise.all([
    client.invalidateQueries({ queryKey: queryKeys.published(code) }),
    client.invalidateQueries({ queryKey: queryKeys.current(code) }),
    client.invalidateQueries({ queryKey: queryKeys.versions(code) }),
    client.invalidateQueries({ queryKey: queryKeys.port(code) }),
  ])
}

export type PublishInput = { layoutId: string; note: string; reviewedBy?: string | null }

/**
 * Publishes the draft (PUBLISHER). The server checks the whole rule catalogue first: a 422 means nothing changed and
 * the draft stays. On success the draft is gone from the cache; the version list and the published layout are read
 * again.
 */
export function usePublishLayout(code: string = defaultPortCode()) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async ({ layoutId, note, reviewedBy }: PublishInput): Promise<PublishResult> => {
      const response = await apiRequest<unknown>(
        `/v1/layouts/${encodeURIComponent(layoutId)}/publish`,
        {
          method: 'POST',
          body: { note, ...(reviewedBy ? { reviewedBy } : {}) },
        },
      )
      return parseResponse('POST publish', publishResultSchema, response.data)
    },
    onSuccess: () => {
      client.removeQueries({ queryKey: queryKeys.draft(code), exact: true })
      return invalidatePublished(client, code)
    },
  })
}

export type RestoreInput = { versionId: string; discardDraft?: boolean }

/**
 * Loads an older version into a new draft (PUBLISHER). Asset ids are those of that version. An open draft is a 409
 * unless discardDraft is set; then it is discarded first by the server.
 */
export function useRestoreVersion(code: string = defaultPortCode()) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async ({ versionId, discardDraft }: RestoreInput): Promise<Layout> => {
      const response = await apiRequest<unknown>(
        `/v1/layouts/${encodeURIComponent(versionId)}/restore`,
        { method: 'POST', body: { discardDraft: discardDraft === true } },
      )
      return parseResponse('POST restore', layoutDocumentSchema, response.data)
    },
    onSuccess: (draft) => {
      client.setQueryData<Layout>(queryKeys.draft(code), draft)
      return client.invalidateQueries({ queryKey: queryKeys.versions(code) })
    },
  })
}

/** Starts a blank draft: the open draft, if any, is discarded first. */
export function useStartBlankLayout(code: string = defaultPortCode()) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (openDraftId: string | null): Promise<Layout> => {
      if (openDraftId !== null) {
        await apiRequest<unknown>(`/v1/layouts/${encodeURIComponent(openDraftId)}`, {
          method: 'DELETE',
        })
      }
      return createDraft(code, 'blank')
    },
    onSuccess: (draft) => {
      client.setQueryData<Layout>(queryKeys.draft(code), draft)
      return client.invalidateQueries({ queryKey: queryKeys.versions(code) })
    },
  })
}
