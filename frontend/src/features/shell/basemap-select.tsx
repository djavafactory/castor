import { BASEMAPS, isBasemap } from '@/lib/basemap'
import { useUiStore } from '@/lib/stores/ui-store'
import { changeBasemap } from './change-basemap'

/** The background map of 2D and 3D, in the bottom strip of Design. A view preference of the user, not of the layout. */
export function BasemapSelect() {
  const basemap = useUiStore((s) => s.basemap)
  return (
    <label className="flex items-center gap-1.5 font-sans text-[13px] text-ink">
      {/* the select keeps its name; the word goes at <= 1100 px to save room */}
      <span className="text-xs text-muted max-wide:hidden">Basemap</span>
      <select
        aria-label="Basemap"
        title="Background map of the 2D and 3D views (your own preference)"
        value={basemap}
        className="h-6 rounded-md border border-line bg-white px-1.5 text-xs text-ink max-wide:w-[136px]"
        onChange={(event) => {
          if (isBasemap(event.target.value)) changeBasemap(event.target.value)
        }}
      >
        {BASEMAPS.map((b) => (
          <option key={b.value} value={b.value}>
            {b.label}
          </option>
        ))}
      </select>
    </label>
  )
}
