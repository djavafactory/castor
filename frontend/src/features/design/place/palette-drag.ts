import type { AssetType } from '@/lib/api'
import { dropHandler, usePlacementStore } from './use-placement'

/** A press that moves more than this many pixels turns into a drag. */
export const DRAG_START_PX = 6

let suppressClick = false

/** True once after a drag ended: the click the browser sends after the release must not start the tool as well. */
export function consumeDragClick(): boolean {
  const was = suppressClick
  suppressClick = false
  return was
}

/**
 * Starts watching a press on a palette tile. After 6 px it becomes a drag: the type follows the pointer as a chip, the
 * ghost shows over the 2D map or the 3D ground, and the release places it with the rules of a click.
 */
export function beginTileDrag(type: AssetType, start: { clientX: number; clientY: number }): void {
  let dragging = false

  const move = (event: PointerEvent) => {
    if (
      !dragging &&
      Math.hypot(event.clientX - start.clientX, event.clientY - start.clientY) > DRAG_START_PX
    ) {
      dragging = true
      usePlacementStore.getState().setDragging(type)
    }
    if (dragging) usePlacementStore.getState().setPointer({ x: event.clientX, y: event.clientY })
  }

  const end = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', end)
    window.removeEventListener('pointercancel', cancel)
    if (!dragging) return
    suppressClick = true
    const { plan } = usePlacementStore.getState()
    // the ghost exists only while the pointer is over a canvas, so a release elsewhere places nothing
    const drop = plan ? dropHandler.current : null
    void (drop ? drop() : Promise.resolve(false)).finally(() => {
      usePlacementStore.getState().setDragging(null)
      usePlacementStore.getState().setPointer(null)
    })
  }

  const cancel = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', end)
    window.removeEventListener('pointercancel', cancel)
    if (dragging) {
      usePlacementStore.getState().setDragging(null)
      usePlacementStore.getState().setPointer(null)
    }
  }

  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', end)
  window.addEventListener('pointercancel', cancel)
}
