import { toast } from 'sonner'
import type { AssetType } from '@/lib/api'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { IN_3D_TOAST, shapeOf } from './draw-rules'

/**
 * Starts the draw tool for a type. The editor store refuses it on the Google basemap (with its own toast); otherwise
 * the type hint is shown, and in the 3D-only view the editor is told where drawing happens.
 */
export function startDraw(type: AssetType): boolean {
  if (shapeOf(type) === null) return false
  useEditorStore.getState().setTool('draw', type.code)
  const tool = useEditorStore.getState().tool
  if (tool?.kind !== 'draw' || tool.typeCode !== type.code) return false
  if (type.hint) toast(type.hint)
  if (useUiStore.getState().viewMode === '3d') toast(IN_3D_TOAST)
  return true
}
