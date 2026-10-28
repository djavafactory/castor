import { act, renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { conflictMessage, defaultPortCode, queryKeys, useUpdateAsset } from '@/lib/api'
import { useEditorStore } from '@/lib/stores/editor-store'
import { API, layoutFixture, layoutWire } from '@/test/fixtures'
import { createTestClient, wrapperFor } from '@/test/query-wrapper'
import { server } from '@/test/setup'
import { cannotRedo, cannotUndo, runRedo, runUndo } from './undo-redo'

vi.mock('sonner', () => ({ toast: Object.assign(vi.fn(), { error: vi.fn() }) }))

const CODE = defaultPortCode()
const DRAFT = `${API}/v1/ports/${CODE}/layouts/draft`
const ASSET = `${API}/v1/layouts/${layoutFixture.versionId}/assets/a-1`

beforeEach(() => {
  useEditorStore.getState().reset()
  vi.mocked(toast).mockClear()
})

function seeded() {
  const client = createTestClient()
  client.setQueryData(queryKeys.draft(CODE), structuredClone(layoutFixture))
  return client
}

function patchHandler() {
  return http.patch(ASSET, async ({ request }) =>
    HttpResponse.json({
      feature: {
        type: 'Feature',
        id: 'a-1',
        geometry: { type: 'Point', coordinates: [101.3, 2.95] },
        properties: { type: 'EXCAVATOR', rowVersion: 4, ...((await request.json()) as object) },
      },
      warnings: [],
    }),
  )
}

const conflict = () =>
  http.patch(ASSET, () =>
    HttpResponse.json(
      { title: 'Conflict', status: 409 },
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )

describe('runUndo and runRedo', () => {
  async function withEntry(client: ReturnType<typeof createTestClient>) {
    const { result } = renderHook(() => useUpdateAsset(CODE), { wrapper: wrapperFor(client) })
    await waitFor(() => expect(result.current).toBeDefined())
    useEditorStore.getState().pushHistory({
      label: 'Moved Excavator 1',
      assetName: 'Excavator 1',
      undo: async () => {
        await result.current.mutateAsync({ id: 'a-1', rowVersion: 3, changes: { name: 'Old' } })
      },
      redo: async () => {
        await result.current.mutateAsync({ id: 'a-1', rowVersion: 3, changes: { name: 'New' } })
      },
    })
  }

  it('logs "Undo: <label>" and moves the step to redo on success', async () => {
    const client = seeded()
    server.use(
      patchHandler(),
      http.get(DRAFT, () => HttpResponse.json(layoutWire())),
    )
    await withEntry(client)

    act(runUndo)

    await waitFor(() =>
      expect(useEditorStore.getState().historyLog[0]).toMatchObject({
        label: 'Undo: Moved Excavator 1',
      }),
    )
    expect(useEditorStore.getState().redoStack).toHaveLength(1)
    expect(useEditorStore.getState().undoStack).toHaveLength(0)
  })

  it('logs "Redo: <label>" and moves the step back to undo on success', async () => {
    const client = seeded()
    server.use(
      patchHandler(),
      http.get(DRAFT, () => HttpResponse.json(layoutWire())),
    )
    await withEntry(client)
    useEditorStore.getState().undo() // pretend the step was already undone: move it to redo

    act(runRedo)

    await waitFor(() =>
      expect(useEditorStore.getState().historyLog[0]).toMatchObject({
        label: 'Redo: Moved Excavator 1',
      }),
    )
    expect(useEditorStore.getState().undoStack).toHaveLength(1)
    expect(useEditorStore.getState().redoStack).toHaveLength(0)
  })

  it('drops the step and shows "Cannot undo" on a 409, not the generic conflict toast', async () => {
    const client = seeded()
    server.use(
      conflict(),
      http.get(DRAFT, () => HttpResponse.json(layoutWire())),
    )
    await withEntry(client)

    act(runUndo)

    await waitFor(() => expect(toast).toHaveBeenCalledWith(cannotUndo('Excavator 1', 'grace')))
    expect(toast).not.toHaveBeenCalledWith(conflictMessage('grace'))
    expect(useEditorStore.getState().undoStack).toHaveLength(0)
    expect(useEditorStore.getState().redoStack).toHaveLength(0)
    // the original "Moved Excavator 1" entry is logged, but no "Undo: ..." line for the failed step
    expect(useEditorStore.getState().historyLog).toEqual([
      { time: expect.any(Number), label: 'Moved Excavator 1' },
    ])
  })

  it('drops the step and shows "Cannot redo" on a 409', async () => {
    const client = seeded()
    server.use(
      conflict(),
      http.get(DRAFT, () => HttpResponse.json(layoutWire())),
    )
    await withEntry(client)
    useEditorStore.getState().undo() // pretend the step was already undone: move it to redo

    act(runRedo)

    await waitFor(() => expect(toast).toHaveBeenCalledWith(cannotRedo('Excavator 1', 'grace')))
    expect(useEditorStore.getState().undoStack).toHaveLength(0)
    expect(useEditorStore.getState().redoStack).toHaveLength(0)
  })

  it('does nothing when there is no step to undo or redo', () => {
    runUndo()
    runRedo()
    expect(toast).not.toHaveBeenCalled()
  })
})
