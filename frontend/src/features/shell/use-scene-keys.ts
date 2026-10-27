import { useEffect } from 'react'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'

/** L toggles the labels, Esc clears the selection. Ignored while typing in a field. */
export function useSceneKeys() {
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null
      if (target && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName)))
        return
      if (event.ctrlKey || event.metaKey || event.altKey) return
      if (event.key === 'Escape') useSelectionStore.getState().clear()
      else if (event.key === 'l' || event.key === 'L') {
        const ui = useUiStore.getState()
        ui.setLabels(!ui.labels)
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [])
}
