import { act, renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import {
  defaultPortCode,
  queryKeys,
  useCreateAsset,
  useDeleteAsset,
  useDraftLayout,
  useUpdateAsset,
  type Layout,
} from '@/lib/api'
import { useEditorStore } from '@/lib/stores/editor-store'
import { API, layoutFixture, layoutWire } from '@/test/fixtures'
import { createTestClient, wrapperFor } from '@/test/query-wrapper'
import { server } from '@/test/setup'

/**
 * PRD 19.1 "undo": place, move and delete, then undo all three: the server draft equals the start state and asset
 * ids are unchanged. One POST, one PATCH, one DELETE (plus their inverses); the re-created asset keeps its id.
 */

const CODE = defaultPortCode()
const DRAFT = `${API}/v1/ports/${CODE}/layouts/draft`
const ASSETS = `${API}/v1/layouts/${layoutFixture.versionId}/assets`

beforeEach(() => useEditorStore.getState().reset())

function seeded() {
  const client = createTestClient()
  client.setQueryData(queryKeys.draft(CODE), structuredClone(layoutFixture))
  return client
}

const draftOf = (client: ReturnType<typeof createTestClient>) =>
  client.getQueryData<Layout>(queryKeys.draft(CODE))

describe('undo: place, move and delete, then undo all three', () => {
  it('leaves the server draft equal to the start state with asset ids unchanged', async () => {
    const client = seeded()
    const startIds = draftOf(client)?.assets.map((a) => a.id)
    // The true server-side state, independent of the client cache (which the test must not take for granted). Kept
    // with a `typeCode` key, as `layoutFixture` and `layoutWire` want it; `toProps` turns it into the wire `type`.
    const server_: Record<string, Record<string, unknown>> = Object.fromEntries(
      layoutFixture.assets.map((a) => [a.id, { ...a }]),
    )
    const toProps = (a: Record<string, unknown>) => {
      const { typeCode, ...rest } = a
      return { ...rest, type: typeCode }
    }
    const feature = (id: string) => ({
      type: 'Feature' as const,
      id,
      geometry: { type: 'Point' as const, coordinates: [101.3, 2.95] },
      properties: toProps(server_[id]!),
    })
    server.use(
      http.post(ASSETS, async ({ request }) => {
        const { type: typeCode, ...rest } = (await request.json()) as Record<string, unknown>
        const id = (rest.id as string | undefined) ?? 'new-1'
        server_[id] = { ...rest, id, typeCode, rowVersion: 0 }
        return HttpResponse.json({ feature: feature(id), warnings: [] }, { status: 201 })
      }),
      http.patch(`${ASSETS}/:id`, async ({ request, params }) => {
        const id = String(params.id)
        const changes = (await request.json()) as Record<string, unknown>
        const rowVersion = Number(server_[id]?.rowVersion ?? 0) + 1
        server_[id] = { ...server_[id], ...changes, rowVersion }
        return HttpResponse.json({ feature: feature(id), warnings: [] })
      }),
      http.delete(`${ASSETS}/:id`, ({ params }) => {
        delete server_[String(params.id)]
        return new HttpResponse(null, { status: 204 })
      }),
      http.get(DRAFT, () =>
        HttpResponse.json(layoutWire({ ...layoutFixture, assets: Object.values(server_) })),
      ),
    )

    const { result } = renderHook(
      () => ({
        create: useCreateAsset(CODE),
        update: useUpdateAsset(CODE),
        remove: useDeleteAsset(CODE),
        ready: useDraftLayout(CODE).isSuccess,
      }),
      { wrapper: wrapperFor(client) },
    )
    await waitFor(() => expect(result.current.ready).toBe(true))

    // Place: one POST, one undo step (DELETE the created asset; redo re-creates it with the same id).
    let current = await result.current.create.mutateAsync({ typeCode: 'EXCAVATOR', name: 'New 1' })
    useEditorStore.getState().pushHistory({
      label: 'Placed New 1',
      assetName: 'New 1',
      undo: async () => {
        await result.current.remove.mutateAsync({ id: current.id, rowVersion: current.rowVersion })
      },
      redo: async () => {
        current = await result.current.create.mutateAsync({
          typeCode: 'EXCAVATOR',
          name: 'New 1',
          id: current.id,
        })
      },
    })
    await waitFor(() => expect(draftOf(client)?.assets.map((a) => a.id)).toContain('new-1'))

    // Move: one PATCH, one undo step (the inverse change).
    const changes = { anchor: [101.31, 2.96] }
    const inverse = { anchor: [101.3, 2.95] }
    current = await result.current.update.mutateAsync({
      id: current.id,
      rowVersion: current.rowVersion,
      changes,
    })
    useEditorStore.getState().pushHistory({
      label: 'Moved New 1',
      assetName: 'New 1',
      undo: async () => {
        current = await result.current.update.mutateAsync({
          id: current.id,
          rowVersion: current.rowVersion,
          changes: inverse,
        })
      },
      redo: async () => {
        current = await result.current.update.mutateAsync({
          id: current.id,
          rowVersion: current.rowVersion,
          changes,
        })
      },
    })

    // Delete: one DELETE, one undo step (re-create with the same id; its links would not come back).
    const deletedBody = { typeCode: 'EXCAVATOR', name: 'New 1', id: current.id }
    await result.current.remove.mutateAsync({ id: current.id, rowVersion: current.rowVersion })
    useEditorStore.getState().pushHistory({
      label: 'Deleted New 1',
      assetName: 'New 1',
      undo: async () => {
        current = await result.current.create.mutateAsync(deletedBody)
      },
      redo: async () => {
        await result.current.remove.mutateAsync({ id: current.id, rowVersion: current.rowVersion })
      },
    })
    await waitFor(() => expect(draftOf(client)?.assets.map((a) => a.id)).toEqual(startIds))

    expect(useEditorStore.getState().undoStack).toHaveLength(3)

    // Undo all three, newest first: delete, then move, then place.
    await act(async () => {
      await useEditorStore.getState().undo()!.undo()
    })
    await waitFor(() => expect(draftOf(client)?.assets.map((a) => a.id)).toContain('new-1'))
    await act(async () => {
      await useEditorStore.getState().undo()!.undo()
    })
    await act(async () => {
      await useEditorStore.getState().undo()!.undo()
    })

    await waitFor(() => expect(draftOf(client)?.assets.map((a) => a.id)).toEqual(startIds))
    expect(useEditorStore.getState().undoStack).toHaveLength(0)
    expect(useEditorStore.getState().redoStack).toHaveLength(3)
  })
})
