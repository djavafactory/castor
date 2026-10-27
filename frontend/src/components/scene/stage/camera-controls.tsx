import { Orbit, RotateCcw, ZoomIn, ZoomOut } from 'lucide-react'
import { useEffect } from 'react'
import { Button, IconButton } from '@/components/ui/button'
import { useUiStore } from '@/lib/stores/ui-store'
import { PRESETS, presetForKey } from './camera-presets'
import { camera, useCameraStore } from './view-state'

/** True when the key press belongs to a text field or another control, not to the stage. */
function typing(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName)
}

/** Keys 1-5 fly to the presets. */
export function usePresetKeys() {
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.ctrlKey || event.metaKey || event.altKey || typing(event.target)) return
      const preset = presetForKey(event.key)
      if (preset) camera.preset(preset.key)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])
}

/** Preset buttons, "Whole port", zoom, rotate and reset, over the lower left of the stage. */
export function CameraControls() {
  usePresetKeys()
  const current = useUiStore((s) => s.cameraPreset)
  const rotating = useCameraStore((s) => s.autoRotate)

  return (
    <div
      role="toolbar"
      aria-label="Camera"
      className="absolute bottom-3 left-3 z-overlay flex flex-wrap items-center gap-1.5 rounded-full border border-line-soft bg-paper/95 p-1 shadow-popover"
    >
      {PRESETS.map((preset, i) => (
        <Button
          key={preset.key}
          size="sm"
          variant={current === preset.key ? 'primary' : 'ghost'}
          aria-pressed={current === preset.key}
          title={`${preset.label} (${i + 1})`}
          onClick={() => camera.preset(preset.key)}
        >
          {preset.label}
        </Button>
      ))}
      <Button
        size="sm"
        variant={current === 'custom' ? 'default' : 'ghost'}
        onClick={() => camera.wholePort()}
      >
        Whole port
      </Button>
      <span aria-hidden="true" className="mx-0.5 h-4 w-px bg-line-soft" />
      <IconButton label="Zoom in" onClick={camera.zoomIn}>
        <ZoomIn aria-hidden="true" />
      </IconButton>
      <IconButton label="Zoom out" onClick={camera.zoomOut}>
        <ZoomOut aria-hidden="true" />
      </IconButton>
      <IconButton label="Rotate" pressed={rotating} onClick={camera.toggleRotate}>
        <Orbit aria-hidden="true" />
      </IconButton>
      <IconButton label="Reset view" onClick={camera.reset}>
        <RotateCcw aria-hidden="true" />
      </IconButton>
    </div>
  )
}
