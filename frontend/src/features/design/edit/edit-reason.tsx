import { useEditPreview } from './edit-preview-store'

/** While a drag is in a position the rules refuse, the reason follows the pointer. */
export function EditReason({ view = '2d' }: { view?: '2d' | '3d' }) {
  const preview = useEditPreview((s) => s.preview)
  if (!preview || preview.valid || !preview.reason || (preview.view ?? '2d') !== view) return null
  return (
    <div
      role="tooltip"
      className="pointer-events-none absolute z-overlay max-w-72 rounded-md bg-ink px-2 py-1 text-xs text-paper shadow-md"
      style={{ left: preview.px + 14, top: preview.py + 14 }}
    >
      {preview.reason}
    </div>
  )
}
