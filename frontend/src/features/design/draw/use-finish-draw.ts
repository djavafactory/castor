import { useCallback } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/lib/api-client'
import {
  useAssetTypes,
  useCreateAsset,
  useDeleteAsset,
  useDraftLayout,
  usePort,
  type AssetType,
  type NewAsset,
} from '@/lib/api'
import { mayTraceOn, GOOGLE_TOAST } from '@/lib/basemap'
import type { LonLat } from '@/lib/geo'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { placedAsset, tracedAsset } from './build-asset'
import { finishProblem, insideRing, nextIdentity, shapeOf, sketchStats } from './draw-rules'

const cannotSave = (reason: string) => `Cannot save this shape: ${reason}`

/** The reason a refused save gives, from the problem details of the server. */
function reasonOf(error: ApiError): string {
  return error.errors[0]?.message ?? error.detail ?? error.title
}

/**
 * Finishes the shape being drawn (Enter, double-click or the button) or places a point asset: the checks run in the
 * ticket's order, then the asset is saved, selected and put on the undo stack. A failed check keeps the sketch.
 */
export function useFinishDraw() {
  const types = useAssetTypes()
  const port = usePort()
  const draft = useDraftLayout()
  const create = useCreateAsset()
  const remove = useDeleteAsset()
  const { mutateAsync } = create
  const { mutateAsync: removeAsset } = remove

  const save = useCallback(
    async (body: NewAsset, name: string): Promise<boolean> => {
      let created
      try {
        created = await mutateAsync(body)
      } catch (error) {
        // Only a refusal of the rules is the editor's to fix; a missing endpoint or a server fault is a failed save
        if (error instanceof ApiError && (error.status === 400 || error.status === 422)) {
          toast(cannotSave(reasonOf(error)))
        }
        return false
      }
      let current = created
      useSelectionStore.getState().select(current.id)
      useEditorStore.getState().pushHistory({
        label: `Drew ${name}`,
        assetName: name,
        undo: async () => {
          await removeAsset({ id: current.id, rowVersion: current.rowVersion })
        },
        redo: async () => {
          // the same id and code come back, as with the undo of a delete
          current = await mutateAsync({ ...body, code: current.code, id: current.id })
        },
      })
      return true
    },
    [mutateAsync, removeAsset],
  )

  const outsideExtent = useCallback(
    (points: readonly LonLat[]) => {
      const ring = port.data?.extent.coordinates[0]
      return ring !== undefined && points.some((p) => !insideRing(p, ring))
    },
    [port.data],
  )

  const typeOf = useCallback(
    (code: string): AssetType | undefined => types.data?.find((t) => t.code === code),
    [types.data],
  )

  /** Finishes the polygon or line in the editor store. Returns true when the asset was saved. */
  const finish = useCallback(async (): Promise<boolean> => {
    const tool = useEditorStore.getState().tool
    if (tool?.kind !== 'draw') return false
    const type = typeOf(tool.typeCode)
    const shape = type && shapeOf(type)
    if (!type || (shape !== 'polygon' && shape !== 'line')) return false

    const problem = finishProblem(shape, sketchStats(shape, tool.points))
    if (problem) {
      toast(problem)
      return false
    }
    const basemap = useUiStore.getState().basemap
    if (!mayTraceOn(basemap)) {
      toast(cannotSave(GOOGLE_TOAST))
      return false
    }
    if (outsideExtent(tool.points)) {
      toast(cannotSave('it lies outside the port extent'))
      return false
    }
    const identity = nextIdentity(type, draft.data?.assets ?? [])
    const saved = await save(
      tracedAsset(type, shape, tool.points, { name: identity.name }, basemap),
      identity.name,
    )
    if (saved) useEditorStore.getState().clearTool()
    return saved
  }, [typeOf, outsideExtent, draft.data, save])

  /** One click with a point type's draw tool places the asset there. */
  const place = useCallback(
    async (at: LonLat): Promise<boolean> => {
      const tool = useEditorStore.getState().tool
      if (tool?.kind !== 'draw') return false
      const type = typeOf(tool.typeCode)
      if (!type || shapeOf(type) !== 'point') return false
      const basemap = useUiStore.getState().basemap
      if (!mayTraceOn(basemap)) {
        toast(cannotSave(GOOGLE_TOAST))
        return false
      }
      if (outsideExtent([at])) {
        toast(cannotSave('it lies outside the port extent'))
        return false
      }
      const identity = nextIdentity(type, draft.data?.assets ?? [])
      const saved = await save(
        placedAsset(type, at, { name: identity.name }, basemap),
        identity.name,
      )
      if (saved) useEditorStore.getState().clearTool()
      return saved
    },
    [typeOf, outsideExtent, draft.data, save],
  )

  return { finish, place, typeOf }
}
