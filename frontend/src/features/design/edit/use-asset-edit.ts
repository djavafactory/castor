import { useCallback, useRef } from 'react'
import { toast } from 'sonner'
import { hits, type MapShape } from '@/components/map/map-scene'
import type { MapSize, MapView } from '@/components/map/map-view'
import type { Local } from '@/lib/geo'
import { patchOf, type EditShape, type EditSubject } from './edit-model'
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
import { notMoved, type Verdict } from './edit-rules'
import { useEditSession } from './edit-session'

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
  const { subject, verdictOf, show, commit: save } = useEditSession(shapes, frameReady)
  const active = useRef<Active | null>(null)

  const commit = useCallback(
    (s: EditSubject, shape: EditShape, label: string) =>
      save(s, patchOf(s, shape), label, notMoved),
    [save],
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
