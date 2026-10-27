import { PIN_MS, useMapStore } from './map-store'
import { localToScreen } from './map-view'
import { northNeedleDeg, scaleBar } from './map-furniture'
import type { Basemap } from '@/lib/basemap'

/** Bottom-left: a bar of a round number of metres, at least 80 px long. */
function ScaleBarView() {
  const k = useMapStore((s) => s.view.k)
  const bar = scaleBar(k)
  return (
    <div
      data-testid="scale-bar"
      className="pointer-events-none absolute bottom-3 left-3 flex flex-col items-start gap-0.5 rounded-md bg-paper/85 px-2 py-1 text-[11px] font-semibold text-ink"
    >
      <span>{bar.label}</span>
      <span
        aria-hidden="true"
        style={{ width: bar.px }}
        className="block h-1.5 border-x-2 border-b-2 border-ink"
      />
    </div>
  )
}

/** Top-right: a circle with a red needle and an N, turned by the rotation of the quay frame. */
function NorthArrow({ frameRotDeg }: { frameRotDeg: number }) {
  return (
    <svg
      role="img"
      aria-label="North arrow"
      data-testid="north-arrow"
      viewBox="-22 -22 44 44"
      className="pointer-events-none absolute top-3 right-3 size-11"
    >
      <circle r="20" className="fill-paper/90 stroke-line-strong" strokeWidth="1.5" />
      <g transform={`rotate(${northNeedleDeg(frameRotDeg)})`} data-testid="north-needle">
        <polygon points="0,-10 3.5,6 -3.5,6" className="fill-bad" />
        <text y="-13" textAnchor="middle" fontSize="8" fontWeight="700" className="fill-ink">
          N
        </text>
      </g>
    </svg>
  )
}

/** The orange pin a search drops for four seconds. */
function Pin() {
  const pin = useMapStore((s) => s.pin)
  const view = useMapStore((s) => s.view)
  const size = useMapStore((s) => s.size)
  if (!pin) return null
  const [px, py] = localToScreen(view, size, pin.x, pin.y)
  return (
    <div
      role="img"
      aria-label="Place found by search"
      data-testid="map-pin"
      data-ms={PIN_MS}
      style={{ left: px, top: py }}
      className="pointer-events-none absolute z-overlay -translate-x-1/2 -translate-y-full"
    >
      <svg viewBox="0 0 24 32" className="size-7 drop-shadow">
        <path
          d="M12 31 C12 31 2 18 2 11 a10 10 0 0 1 20 0 C22 18 12 31 12 31 Z"
          className="fill-signal stroke-paper"
          strokeWidth="2"
        />
        <circle cx="12" cy="11" r="3.5" className="fill-paper" />
      </svg>
    </div>
  )
}

/** Everything drawn over the canvas: scale bar, north arrow, the pin and, on Google, the locator note. */
export function MapFurniture({
  frameRotDeg,
  basemap,
  note,
}: {
  frameRotDeg: number
  basemap: Basemap
  note: string
}) {
  return (
    <>
      <ScaleBarView />
      <NorthArrow frameRotDeg={frameRotDeg} />
      <Pin />
      {basemap === 'google' && (
        <p
          role="note"
          className="pointer-events-none absolute bottom-3 left-1/2 max-w-[70%] -translate-x-1/2 rounded-full bg-ink px-4 py-1.5 text-center text-xs font-medium text-paper"
        >
          {note}
        </p>
      )}
    </>
  )
}
