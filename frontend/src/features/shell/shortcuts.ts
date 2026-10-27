import type { Mode } from '@/lib/stores/ui-store'

export interface Shortcut {
  keys: string
  action: string
  /** Only in this mode; without it, in both. */
  mode?: Mode
}

/** The keyboard table of the help popover. */
export const SHORTCUTS: readonly Shortcut[] = [
  { keys: '1-5', action: 'Camera presets' },
  { keys: 'P', action: 'Hide or show the side panel' },
  { keys: 'F', action: 'Fullscreen', mode: 'monitor' },
  { keys: 'L', action: 'Labels' },
  { keys: '?', action: 'Help' },
  { keys: 'Esc', action: 'Clear selection' },
  { keys: 'A', action: 'Hide or show the palette', mode: 'design' },
  { keys: 'R', action: 'Rotate the ghost', mode: 'design' },
  { keys: 'M', action: 'Measure', mode: 'design' },
  { keys: 'Ctrl+Z / Ctrl+Y', action: 'Undo / redo', mode: 'design' },
  { keys: 'Ctrl+S', action: 'Save draft check', mode: 'design' },
  { keys: 'Delete', action: 'Delete the selection', mode: 'design' },
  { keys: 'Enter', action: 'Finish drawing', mode: 'design' },
  { keys: 'Backspace', action: 'Remove last point', mode: 'design' },
]

export function shortcutsFor(mode: Mode): Shortcut[] {
  return SHORTCUTS.filter((s) => s.mode === undefined || s.mode === mode)
}
