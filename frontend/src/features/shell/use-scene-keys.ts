import { useEffect } from 'react'
import { camera } from '@/components/scene/stage/view-state'
import { presetForKey } from '@/components/scene/stage/camera-presets'
import { useAuthStore } from '@/lib/stores/auth-store'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { rotated } from '@/features/design/place/placement-plan'
import { runRedo, runUndo } from '@/features/design/history/undo-redo'
import { toggleFullscreen } from './fullscreen'

const ROTATE_STEP_DEG = 15

/** True when the key press belongs to a text field or another control, not to the stage. */
function typing(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName)
}

/**
 * The shortcuts of both modes. Ignored while typing in a field, with Alt, with Ctrl/Cmd (other than the Design
 * undo, redo and save keys) and when signed out.
 */
export function useSceneKeys() {
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (typing(event.target) || useAuthStore.getState().credential === null) return
      const ui = useUiStore.getState()
      const design = ui.mode === 'design'
      const key = event.key

      if ((event.ctrlKey || event.metaKey) && !event.altKey) {
        if (!design) return
        const lower = key.toLowerCase()
        if (lower === 'z' || lower === 'y' || lower === 's') event.preventDefault()
        if (lower === 'z') (event.shiftKey ? runRedo : runUndo)()
        else if (lower === 'y') runRedo()
        else if (lower === 's') useEditorStore.getState().requestSave()
        return
      }
      if (event.ctrlKey || event.metaKey || event.altKey) return

      const preset = presetForKey(key)
      if (preset) {
        camera.preset(preset.key)
        return
      }
      switch (key) {
        case 'Escape':
          useSelectionStore.getState().clear()
          if (design) useEditorStore.getState().clearTool()
          break
        case 'l':
        case 'L':
          ui.setLabels(!ui.labels)
          break
        case 'p':
        case 'P':
          ui.setPanelHidden(!ui.panelHidden)
          break
        case '?':
          ui.setHelpOpen(!ui.helpOpen)
          break
        case 'f':
        case 'F':
          if (!design) void toggleFullscreen()
          break
        case 'a':
        case 'A':
          if (design) ui.setPaletteHidden(!ui.paletteHidden)
          break
        case 'm':
        case 'M':
          if (design) useEditorStore.getState().setTool('measure')
          break
        case 'r':
        case 'R':
          if (design) {
            const tool = useEditorStore.getState().tool
            if (tool) {
              useEditorStore.getState().setRotation(rotated(tool.rotationDeg, ROTATE_STEP_DEG))
            }
          }
          break
        case 'Backspace':
          if (design) useEditorStore.getState().removeLastPoint()
          break
        case 'Delete':
          if (design) useEditorStore.getState().requestDelete()
          break
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [])
}
