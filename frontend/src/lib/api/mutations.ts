import { useMutation, useQueryClient, type QueryClient, type QueryKey } from '@tanstack/react-query'
import { apiRequest } from '@/lib/api-client'
import { defaultPortCode, queryKeys } from './hooks'
import { layoutAssetSchema, layoutSchema, type Layout, type LayoutAsset } from './schemas'
import { parseResponse } from './validated'

/**
 * Mutation hooks for the designer (DESIGNER-04) and publishing (PUB). Each one updates the cached draft at once
 * (optimistic), puts the previous value back when the server refuses and refetches when it settles.
 * The endpoints are fixed by DESIGNER-04 and PUB; until those tickets land they answer 404.
 */

type Snapshot = { key: QueryKey; previous: Layout | undefined; changed: boolean }

const draftPath = (code: string) => `/v1/ports/${encodeURIComponent(code)}/layout/draft`

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
export type AssetPatch = { id: string; rowVersion: number; changes: Record<string, unknown> }

/** Id given to an asset until the server has answered. */
export const TEMP_ID_PREFIX = 'pending-'

export function useCreateAsset(code: string = defaultPortCode()) {
  const client = useQueryClient()
  const key = queryKeys.draft(code)
  return useMutation({
    mutationFn: async (asset: NewAsset): Promise<LayoutAsset> => {
      const response = await apiRequest<unknown>(`${draftPath(code)}/assets`, {
        method: 'POST',
        body: asset,
      })
      return parseResponse('POST assets', layoutAssetSchema, response.data)
    },
    onMutate: (asset) =>
      applyOptimistic(client, key, (layout) => ({
        ...layout,
        assets: [
          ...layout.assets,
          { ...asset, id: `${TEMP_ID_PREFIX}${crypto.randomUUID()}`, rowVersion: 0 },
        ],
      })),
    onError: (_error, _asset, snapshot) => rollback(client, snapshot),
    onSettled: () => client.invalidateQueries({ queryKey: key }),
  })
}

export function useUpdateAsset(code: string = defaultPortCode()) {
  const client = useQueryClient()
  const key = queryKeys.draft(code)
  return useMutation({
    mutationFn: async ({ id, rowVersion, changes }: AssetPatch): Promise<LayoutAsset> => {
      const response = await apiRequest<unknown>(
        `${draftPath(code)}/assets/${encodeURIComponent(id)}`,
        {
          method: 'PATCH',
          body: changes,
          rowVersion,
        },
      )
      return parseResponse('PATCH asset', layoutAssetSchema, response.data)
    },
    onMutate: ({ id, changes }) =>
      applyOptimistic(client, key, (layout) => ({
        ...layout,
        assets: layout.assets.map((asset) => (asset.id === id ? { ...asset, ...changes } : asset)),
      })),
    onError: (_error, _patch, snapshot) => rollback(client, snapshot),
    onSettled: () => client.invalidateQueries({ queryKey: key }),
  })
}

export function useDeleteAsset(code: string = defaultPortCode()) {
  const client = useQueryClient()
  const key = queryKeys.draft(code)
  return useMutation({
    mutationFn: async ({ id, rowVersion }: { id: string; rowVersion: number }): Promise<void> => {
      await apiRequest<unknown>(`${draftPath(code)}/assets/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        rowVersion,
      })
    },
    onMutate: ({ id }) =>
      applyOptimistic(client, key, (layout) => ({
        ...layout,
        assets: layout.assets.filter((asset) => asset.id !== id),
      })),
    onError: (_error, _target, snapshot) => rollback(client, snapshot),
    onSettled: () => client.invalidateQueries({ queryKey: key }),
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
