import { useCallback, useMemo } from 'react'
import { toast } from 'sonner'
import { layerOf } from '@/components/scene/layers'
import type { MapShape } from '@/components/map/map-scene'
import { ApiError } from '@/lib/api-client'
import { useAssetTypes, useDraftLayout, usePort, useUpdateAsset } from '@/lib/api'
import { fromLonLat, type Local } from '@/lib/geo'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { inverseOf, ringsOfShape, subjectOf, type EditShape, type EditSubject } from './edit-model'
import { useEditPreview } from './edit-preview-store'
import { checkEdit, type Neighbour, type RuleContext, type Verdict } from './edit-rules'

const reasonOf = (error: ApiError) => error.errors[0]?.message ?? error.detail ?? error.title

const RING = (ring: unknown): Local[] | null =>
  Array.isArray(ring) ? (ring as number[][]).map((p) => fromLonLat(p[0]!, p[1]!)) : null

function openRing(ring: Local[] | null): Local[] | null {
  if (!ring || ring.length < 4) return null
  const first = ring[0]!
  const last = ring.at(-1)!
  return first[0] === last[0] && first[1] === last[1] ? ring.slice(0, -1) : ring
}

/**
 * What the 2D handles and the 3D gizmo share: the editable form of an asset of the draft, the rules an edit is checked
 * against, the preview and the save. `shapes` are the assets of the draft in local metres (the neighbours of a rule).
 */
export function useEditSession(shapes: readonly MapShape[], frameReady: boolean) {
  const types = useAssetTypes()
  const port = usePort()
  const draft = useDraftLayout()
  const { mutateAsync } = useUpdateAsset()
  const mode = useUiStore((s) => s.mode)
  // Locking or unlocking a layer re-reads the subject
  const lockedLayers = useUiStore((s) => s.lockedLayers)
  const tool = useEditorStore((s) => s.tool)
  const selectedId = useSelectionStore((s) => s.selectedAssetId)

  const typeMap = useMemo(() => new Map((types.data ?? []).map((t) => [t.code, t])), [types.data])

  /** An asset of the draft, when it may be edited: Design mode, no drawing tool, a draft, an unlocked layer. */
  const subjectFor = useCallback(
    (id: string | null): EditSubject | null => {
      if (!frameReady || mode !== 'design' || tool !== null || id === null) return null
      if (draft.data?.status !== 'DRAFT') return null
      const asset = draft.data.assets.find((a) => a.id === id)
      const type = asset && typeMap.get(asset.typeCode)
      if (!asset || !type) return null
      // A locked layer shows no handles (REPLICA-07)
      const layer = layerOf(asset.typeCode, type.category)
      if (layer !== undefined && lockedLayers[layer] === true) return null
      return subjectOf(asset, type)
    },
    [frameReady, mode, tool, draft.data, typeMap, lockedLayers],
  )

  const subject = useMemo(() => subjectFor(selectedId), [subjectFor, selectedId])

  const context = useMemo((): Omit<RuleContext, 'type' | 'overridden'> | null => {
    if (!frameReady || !port.data) return null
    const extent = openRing(RING(port.data.extent.coordinates[0]))
    const land = openRing(RING(port.data.landExtent?.coordinates[0]))
    const details = new Map(draft.data?.assets.map((a) => [a.id, a]) ?? [])
    const neighbours: Neighbour[] = shapes.map((s) => {
      const zone = (details.get(s.id)?.details as { zone?: { allowedTypes?: unknown } } | undefined)
        ?.zone
      return {
        id: s.id,
        name: s.name,
        typeCode: s.typeCode,
        rings: s.rings,
        closed: s.closed,
        allowedTypes: Array.isArray(zone?.allowedTypes) ? (zone.allowedTypes as string[]) : [],
      }
    })
    return { extent, land, hasQuay: port.data.quayLine !== null, neighbours, types: typeMap }
  }, [frameReady, port.data, draft.data, shapes, typeMap])

  const verdictOf = useCallback(
    (s: EditSubject, shape: EditShape): Verdict => {
      const type = typeMap.get(s.typeCode)
      if (!type || !context) return { ok: true }
      const override = (s.asset.override as { reason?: unknown } | undefined)?.reason
      return checkEdit(s, shape, {
        ...context,
        neighbours: context.neighbours.filter((n) => n.id !== s.id),
        type,
        overridden: typeof override === 'string' && override.trim() !== '',
      })
    },
    [typeMap, context],
  )

  const show = useCallback(
    (
      s: EditSubject,
      shape: EditShape,
      verdict: Verdict,
      px: number,
      py: number,
      heightM?: number,
    ) => {
      const { rings, closed } = ringsOfShape(shape)
      useEditPreview.getState().setPreview({
        assetId: s.id,
        shape,
        rings,
        closed,
        valid: verdict.ok,
        reason: verdict.ok ? null : verdict.reason,
        px,
        py,
        ...(heightM === undefined ? {} : { heightM }),
      })
    },
    [],
  )

  /**
   * Stores an edit that passed the rules: one PATCH, one undo step. A refusal of the server is shown as
   * `refused(reason)`. Returns whether it was saved.
   */
  const commit = useCallback(
    async (
      s: EditSubject,
      changes: Record<string, unknown>,
      label: string,
      refused: (reason: string) => string,
    ): Promise<boolean> => {
      const inverse = inverseOf(s.asset, changes)
      let version = s.rowVersion
      try {
        version = (await mutateAsync({ id: s.id, rowVersion: version, changes })).rowVersion
      } catch (error) {
        // A refusal of the rules is the editor's to fix, not a failed save to retry
        if (error instanceof ApiError && (error.status === 400 || error.status === 422)) {
          useEditorStore.getState().endSave(s.id)
          useEditorStore.setState((state) => ({
            failedSaves: state.failedSaves.filter((id) => id !== s.id),
          }))
          toast(refused(reasonOf(error)))
        }
        return false
      }
      useEditorStore.getState().pushHistory({
        label,
        assetName: s.name,
        undo: async () => {
          version = (await mutateAsync({ id: s.id, rowVersion: version, changes: inverse }))
            .rowVersion
        },
        redo: async () => {
          version = (await mutateAsync({ id: s.id, rowVersion: version, changes })).rowVersion
        },
      })
      return true
    },
    [mutateAsync],
  )

  return { subject, subjectFor, typeMap, verdictOf, show, commit }
}
