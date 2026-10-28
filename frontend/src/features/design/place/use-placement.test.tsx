import { act, renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setActiveFrame } from '@/lib/geo'
import type { AssetType } from '@/lib/api'
import { useDraftLayout } from '@/lib/api'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { API, assetTypesFixture, layoutFixture, layoutWire } from '@/test/fixtures'
import { wrapperFor } from '@/test/query-wrapper'
import { server } from '@/test/setup'
import { planPlacement } from './placement-plan'
import { usePlaceAsset, usePlacementStore, cannotPlace } from './use-placement'

vi.mock('sonner', () => ({ toast: vi.fn() }))

const EXCAVATOR = assetTypesFixture[0] as unknown as AssetType
const ASSETS = `${API}/v1/layouts/${layoutFixture.versionId}/assets`
const site = { extent: [], land: null, quay: null }

const feature = (id: string) => ({
  type: 'Feature',
  id,
  geometry: { type: 'Polygon', coordinates: [] },
  properties: { type: 'EXCAVATOR', code: 'EX-0003', name: 'Excavator 3', rowVersion: 0 },
})

beforeEach(() => {
  setActiveFrame({ lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 })
  useEditorStore.getState().reset()
  useSelectionStore.getState().clear()
  vi.mocked(toast).mockClear()
  usePlacementStore
    .getState()
    .set(planPlacement(EXCAVATOR, [100, 200], 0, site, [], new Map()), null)
  server.use(
    http.get(`${API}/v1/ports/WESTPORTS/layouts/draft`, () => HttpResponse.json(layoutWire())),
  )
})

async function place(keepTool = false) {
  const { result } = renderHook(
    () => ({ place: usePlaceAsset(), ready: useDraftLayout().isSuccess }),
    { wrapper: wrapperFor() },
  )
  await waitFor(() => expect(result.current.ready).toBe(true))
  let ok = false
  await act(async () => {
    ok = await result.current.place({ keepTool })
  })
  return ok
}

describe('placing the asset of the ghost', () => {
  it('saves it with its anchor, heading and size, selects it and records "Placed <name>"', async () => {
    let body: Record<string, unknown> = {}
    server.use(
      http.post(ASSETS, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ feature: feature('new-1'), warnings: [] }, { status: 201 })
      }),
    )
    useEditorStore.getState().setTool('place', 'EXCAVATOR')

    expect(await place()).toBe(true)

    expect(body).toMatchObject({
      type: 'EXCAVATOR',
      lengthM: 10,
      widthM: 3.2,
      provenance: {
        source: 'OPERATOR',
        accuracy: 'APPROXIMATE',
        note: 'Placed in the layout designer',
      },
    })
    expect(body.anchor).toHaveLength(2)
    expect(typeof body.headingDeg).toBe('number')
    expect(body).not.toHaveProperty('code')
    expect(useSelectionStore.getState().selectedAssetId).toBe('new-1')
    expect(useEditorStore.getState().undoStack.at(-1)?.label).toBe('Placed Excavator 3')
    expect(useEditorStore.getState().tool).toBeNull()
  })

  it('keeps the tool for the next placement while Shift is held', async () => {
    server.use(
      http.post(ASSETS, () =>
        HttpResponse.json({ feature: feature('new-2'), warnings: [] }, { status: 201 }),
      ),
    )
    useEditorStore.getState().setTool('place', 'EXCAVATOR')
    expect(await place(true)).toBe(true)
    expect(useEditorStore.getState().tool).toMatchObject({ kind: 'place', typeCode: 'EXCAVATOR' })
  })

  it('refuses an invalid spot with the reason and saves nothing', async () => {
    let posted = false
    server.use(
      http.post(ASSETS, () => {
        posted = true
        return HttpResponse.json({}, { status: 201 })
      }),
    )
    usePlacementStore.getState().set(usePlacementStore.getState().plan, 'Outside the land area')

    expect(await place()).toBe(false)

    expect(toast).toHaveBeenCalledWith(cannotPlace('Outside the land area'))
    expect(cannotPlace('Outside the land area')).toBe('Cannot place here: Outside the land area')
    expect(posted).toBe(false)
    expect(useEditorStore.getState().undoStack).toHaveLength(0)
  })

  it('shows the reason of a 422 and removes the asset again', async () => {
    server.use(
      http.post(ASSETS, () =>
        HttpResponse.json(
          {
            title: 'The shape breaks layout rules',
            status: 422,
            errors: [{ code: 'OVERLAP', message: 'Overlaps Warehouse 1' }],
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    expect(await place()).toBe(false)
    expect(toast).toHaveBeenCalledWith(cannotPlace('Overlaps Warehouse 1'))
    expect(useSelectionStore.getState().selectedAssetId).toBeNull()
  })
})
