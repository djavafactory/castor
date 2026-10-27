import { basemapLabel, mayTraceOn, type Basemap } from '@/lib/basemap'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore } from '@/lib/stores/ui-store'

/**
 * Switches the background map of 2D and 3D. A tool that would store geometry is dropped when the new basemap may not
 * be traced, and in Design the switch goes into the edit history as "Basemap: <label>" (undo brings the old one back).
 */
export function changeBasemap(next: Basemap): void {
  const ui = useUiStore.getState()
  const previous = ui.basemap
  if (next === previous) return
  ui.setBasemap(next)

  const editor = useEditorStore.getState()
  if (!mayTraceOn(next) && editor.tool && editor.tool.kind !== 'measure') editor.clearTool()
  if (ui.mode !== 'design') return
  editor.pushHistory({
    label: `Basemap: ${basemapLabel(next)}`,
    undo: () => useUiStore.getState().setBasemap(previous),
    redo: () => useUiStore.getState().setBasemap(next),
  })
}
