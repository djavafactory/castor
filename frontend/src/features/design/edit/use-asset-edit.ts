import { useCallback, useMemo, useRef } from 'react'
import { toast } from 'sonner'
import { layerOf } from '@/components/scene/layers'
import { hits, type MapShape } from '@/components/map/map-scene'
import type { MapSize, MapView } from '@/components/map/map-view'
import { ApiError } from '@/lib/api-client'
import { useAssetTypes, useDraftLayout, usePort, useUpdateAsset } from '@/lib/api'
import { fromLonLat, type Local } from '@/lib/geo'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import {
  inverseOf,
  patchOf,
  ringsOfShape,
  subjectOf,
  type EditShape,
  type EditSubject,
} from './edit-model'
import { useEditPreview } from './edit-preview-store'
import {
  MOVE_THRESHOLD_PX,
  editableHandles,
  handleAt,
  insertVertex,
  moveBerthEnd,
  moveShape,
  moveVertex,
  removeVertex,
  resizeCircle,
  resizeRect,
  rotateRect,
  type Handle,
} from './edit-ops'
import { checkEdit, notMoved, type Neighbour, type RuleContext, type Verdict } from './edit-rules'

type Gesture =
  | { kind: 'move' }
  | { kind: 'corner' | 'end' | 'vertex'; index: number; base: EditShape; added: boolean }
  | { kind: 'rotate' }
  | { kind: 'radius' }

interface Active {
  gesture: Gesture
  pointerId: number
  startPx: number
  startPy: number
  start: Local
  moved: boolean
  shape: EditShape
  verdict: Verdict
}

/** What a pointer press did: nothing for the editor, a click that selects, or a gesture that now owns the pointer. */
export type EditPress = 'none' | 'gesture'

/** A press this close to the selected shape starts a move. */
const MOVE_REACH_PX = 7

const reasonOf = (error: ApiError) => error.errors[0]?.message ?? error.detail ?? error.title

const RING = (ring: unknown): Local[] | null =>
  Array.isArray(ring) ? (ring as number[][]).map((p) => fromLonLat(p[0]!, p[1]!)) : null

function openRing(ring: Local[] | null): Local[] | null {
  if (!ring || ring.length < 4) return null
  const first = ring[0]!
  const last = ring.at(-1)!
  return first[0] === last[0] && first[1] === last[1] ? ring.slice(0, -1) : ring
}

function labelOf(active: Active, name: string): string {
  const g = active.gesture
  switch (g.kind) {
    case 'move':
      return `Moved ${name}`
    case 'rotate':
      return `Rotated ${name}`
    case 'vertex':
      return g.added ? `Added a vertex to ${name}` : `Moved a vertex of ${name}`
    default:
      return `Resized ${name}`
  }
}

/**
 * The 2D editing handles (DESIGNER-06): move, resize, rotate and vertices of the selected asset. The map hands its
 * pointer events to {@link press}, {@link drag} and {@link release}; a gesture shows its result in the preview store
 * (the 2D map and the 3D ghost both draw it) and is saved, checked against the rules, only when the pointer is released.
 */
export function useAssetEdit(shapes: readonly MapShape[], frameReady: boolean) {
  const types = useAssetTypes()
  const port = usePort()
  const draft = useDraftLayout()
  const update = useUpdateAsset()
  const { mutateAsync } = update
  const mode = useUiStore((s) => s.mode)
  // Locking or unlocking a layer re-reads the subject
  const lockedLayers = useUiStore((s) => s.lockedLayers)
  const tool = useEditorStore((s) => s.tool)
  const selectedId = useSelectionStore((s) => s.selectedAssetId)
  const active = useRef<Active | null>(null)

  const typeMap = useMemo(() => new Map((types.data ?? []).map((t) => [t.code, t])), [types.data])

  /** The selected asset, when the handles may edit it: Design mode, no drawing tool, a draft, an unlocked layer. */
  const subject = useMemo((): EditSubject | null => {
    if (!frameReady || mode !== 'design' || tool !== null || selectedId === null) return null
    if (draft.data?.status !== 'DRAFT') return null
    const asset = draft.data.assets.find((a) => a.id === selectedId)
    const type = asset && typeMap.get(asset.typeCode)
    if (!asset || !type) return null
    // A locked layer shows no handles (REPLICA-07)
    const layer = layerOf(asset.typeCode, type.category)
    if (layer !== undefined && lockedLayers[layer] === true) return null
    return subjectOf(asset, type)
  }, [frameReady, mode, tool, selectedId, draft.data, typeMap, lockedLayers])

  const context = useMemo((): Omit<RuleContext, 'type' | 'overridden'> | null => {
    if (!frameReady || !port.data) return null
    const extent = openRing(RING(port.data.extent.coordinates[0]))
    const land = openRing(RING(port.data.landExtent?.coordinates[0]))
    const details = new Map(draft.data?.assets.map((a) => [a.id, a]) ?? [])
    const neighbours: Neighbour[] = shapes
      .filter((s) => s.id !== selectedId)
      .map((s) => {
        const zone = (
          details.get(s.id)?.details as { zone?: { allowedTypes?: unknown } } | undefined
        )?.zone
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
  }, [frameReady, port.data, draft.data, shapes, selectedId, typeMap])

  const verdictOf = useCallback(
    (s: EditSubject, shape: EditShape): Verdict => {
      const type = typeMap.get(s.typeCode)
      if (!type || !context) return { ok: true }
      const override = (s.asset.override as { reason?: unknown } | undefined)?.reason
      return checkEdit(s, shape, {
        ...context,
        type,
        overridden: typeof override === 'string' && override.trim() !== '',
      })
    },
    [typeMap, context],
  )

  const show = useCallback(
    (s: EditSubject, shape: EditShape, verdict: Verdict, px: number, py: number) => {
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
      })
    },
    [],
  )

  /** Stores an edit that passed the rules: one PATCH, one undo step. Returns whether it was saved. */
  const commit = useCallback(
    async (s: EditSubject, shape: EditShape, label: string): Promise<boolean> => {
      const changes = patchOf(s, shape)
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
          toast(notMoved(reasonOf(error)))
        }
        return false
      }
      useEditorStore.getState().pushHistory({
        label,
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

  /** Alt+click on a vertex removes it while more than 3 (polygon) / 2 (line) points remain. */
  const removeAt = useCallback(
    async (s: EditSubject, index: number) => {
      const next = removeVertex(s.shape, index)
      if (!next) return
      const verdict = verdictOf(s, next)
      if (!verdict.ok) {
        toast(notMoved(verdict.reason))
        return
      }
      show(s, next, verdict, 0, 0)
      await commit(s, next, `Removed a vertex of ${s.name}`)
      useEditPreview.getState().setPreview(null)
    },
    [verdictOf, show, commit],
  )

  const press = useCallback(
    (
      event: { pointerId: number; altKey: boolean },
      px: number,
      py: number,
      local: Local,
      view: MapView,
      size: MapSize,
    ): EditPress => {
      if (!subject) return 'none'
      const handle: Handle | null = handleAt(editableHandles(subject, view.k), view, size, px, py)
      const base = {
        pointerId: event.pointerId,
        startPx: px,
        startPy: py,
        start: local,
        moved: false,
      }
      const idle = { shape: subject.shape, verdict: { ok: true } as Verdict }
      if (handle) {
        if (event.altKey && handle.kind === 'vertex') {
          void removeAt(subject, handle.index)
          return 'gesture'
        }
        let gesture: Gesture
        switch (handle.kind) {
          case 'corner':
            gesture = { kind: 'corner', index: handle.index, base: subject.shape, added: false }
            break
          case 'end':
            gesture = { kind: 'end', index: handle.index, base: subject.shape, added: false }
            break
          case 'vertex':
            gesture = { kind: 'vertex', index: handle.index, base: subject.shape, added: false }
            break
          case 'mid':
            // Dragging a midpoint adds a vertex there and carries it
            gesture = {
              kind: 'vertex',
              index: handle.index + 1,
              base: insertVertex(subject.shape, handle.index),
              added: true,
            }
            break
          case 'rotate':
            gesture = { kind: 'rotate' }
            break
          case 'radius':
            gesture = { kind: 'radius' }
            break
        }
        active.current = { ...base, ...idle, gesture }
        return 'gesture'
      }
      const hit = shapes.find((s) => s.id === subject.id)
      if (hit && hits(hit, local[0], local[1], MOVE_REACH_PX / view.k)) {
        active.current = { ...base, ...idle, gesture: { kind: 'move' } }
        return 'gesture'
      }
      return 'none'
    },
    [subject, shapes, removeAt],
  )

  const drag = useCallback(
    (
      event: { pointerId: number; shiftKey: boolean },
      px: number,
      py: number,
      local: Local,
    ): boolean => {
      const a = active.current
      if (!a || !subject || a.pointerId !== event.pointerId) return false
      if (!a.moved && Math.hypot(px - a.startPx, py - a.startPy) < MOVE_THRESHOLD_PX) return true
      a.moved = true
      const g = a.gesture
      let shape: EditShape
      switch (g.kind) {
        case 'move':
          shape = moveShape(subject, subject.shape, local[0] - a.start[0], local[1] - a.start[1])
          break
        case 'corner':
          shape = resizeRect(subject, g.index, local)
          break
        case 'end':
          shape = moveBerthEnd(subject, g.index, local)
          break
        case 'vertex':
          shape = moveVertex(g.base, g.index, local)
          break
        case 'rotate':
          shape = rotateRect(subject.shape, local, event.shiftKey)
          break
        case 'radius':
          shape = resizeCircle(subject, local)
          break
      }
      a.shape = shape
      a.verdict = verdictOf(subject, shape)
      show(subject, shape, a.verdict, px, py)
      return true
    },
    [subject, verdictOf, show],
  )

  /**
   * Ends the gesture. `click` is true for a press on the shape that never moved: the map then handles it as a click.
   * An edit the rules refuse snaps back with a toast; a valid one is saved in the background and the preview stays on
   * screen until the save has answered.
   */
  const release = useCallback(
    (event: { pointerId: number }): { handled: boolean; click: boolean } => {
      const a = active.current
      if (!a || a.pointerId !== event.pointerId) return { handled: false, click: false }
      active.current = null
      const clear = () => useEditPreview.getState().setPreview(null)
      if (!subject || !a.moved) {
        clear()
        return { handled: true, click: a.gesture.kind === 'move' }
      }
      if (!a.verdict.ok) {
        clear()
        toast(notMoved(a.verdict.reason))
        return { handled: true, click: false }
      }
      void commit(subject, a.shape, labelOf(a, subject.name)).finally(clear)
      return { handled: true, click: false }
    },
    [subject, commit],
  )

  const cancel = useCallback(() => {
    active.current = null
    useEditPreview.getState().setPreview(null)
  }, [])

  return { subject, press, drag, release, cancel }
}
