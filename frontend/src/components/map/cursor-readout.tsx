import { useMapStore } from './map-store'
import { CURSOR_TOOLTIP, cursorReadout } from './map-furniture'

/** The bottom strip's cursor position: WGS84 latitude, longitude and local metres of the pointer on the 2D map or the 3D ground. */
export function CursorReadout() {
  const cursor = useMapStore((s) => s.cursor)
  return (
    <span
      data-testid="cursor-readout"
      title={CURSOR_TOOLTIP}
      className="min-w-[27ch] text-left whitespace-nowrap"
    >
      {cursor ? cursorReadout(cursor.lon, cursor.lat, cursor.x, cursor.y) : 'Cursor: -'}
    </span>
  )
}
