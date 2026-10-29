import { useEffect, useMemo, useState, type SyntheticEvent } from 'react'
import { ChevronRight, Search } from 'lucide-react'
import { ModelThumbnail } from '@/components/scene/models/model-palette'
import { getModelSpec } from '@/components/scene/models/registry'
import { Tooltip } from '@/components/ui/overlays'
import { useAssetTypes, type AssetType } from '@/lib/api'
import { cn } from '@/lib/utils'
import { useEditorStore } from '@/lib/stores/editor-store'
import { startDraw } from './draw/start-draw'
import { beginTileDrag, consumeDragClick } from './place/palette-drag'
import { usePlacementStore } from './place/use-placement'

/** Names of the palette groups (ref.palette_group); the server sends the types in palette order. */
export const GROUP_LABELS: Record<string, string> = {
  CRANES: 'Cranes & cargo handling',
  HANDLING: 'Material handling',
  STORAGE: 'Storage & infrastructure',
  TRANSPORT: 'Transport & logistics',
  MARINE: 'Berth & marine facilities',
  ROADS: 'Roads',
  RORO: 'RoRo',
  ZONES: 'Zones & safety',
}

const groupLabel = (code: string) => GROUP_LABELS[code] ?? code

export const tileLabel = (type: AssetType) =>
  type.placementMode === 'DRAW' ? `${type.label} (draw)` : type.label

export const tileTip = (type: AssetType) => `${type.hint} Click to place, or drag onto the map.`

const metres = (n: number) => String(Number(n.toFixed(2)))

/** The default size of a type in words: "18 x 9 x 28 m", "18 m across, 26 m high", "2.4 m wide, 7 m high". */
export function defaultSizeText(type: AssetType): string | null {
  const { lengthM: l, widthM: w, heightM: h, radiusM: r, lineWidthM: lw } = type.defaults
  const high = h == null ? null : `${metres(h)} m high`
  if (r != null) return [`${metres(r * 2)} m across`, high].filter(Boolean).join(', ')
  if (type.placementMode === 'DRAW') {
    const parts = [lw == null ? null : `${metres(lw)} m wide`, high].filter(Boolean)
    return parts.length > 0 ? parts.join(', ') : null
  }
  const sides = [l, w, h].filter((v): v is number => v != null)
  return sides.length > 0 ? `${sides.map(metres).join(' x ')} m` : null
}

/** The 3D thumbnail of the type's model; a coloured type icon until the model exists (neutral-box fallback). */
function TypeThumbnail({ type }: { type: AssetType }) {
  if (getModelSpec(type.modelKey)) return <ModelThumbnail modelKey={type.modelKey} />
  return (
    <span
      aria-hidden="true"
      data-testid="type-icon"
      className="flex h-[60px] w-[84px] shrink-0 items-center justify-center rounded-md bg-paper-2"
    >
      <span className="size-7 rounded-md" style={{ background: type.colourHex }} />
    </span>
  )
}

/** Space between the palette's edge and a tile's hover card, pixels. */
const CARD_GAP = 12

/** What the hover card of a tile shows: the model large, what the asset is for, its default size and how to place it. */
function PurposeCard({ type }: { type: AssetType }) {
  const size = defaultSizeText(type)
  return (
    <div className="mt-1 flex flex-col gap-1.5" data-testid="palette-purpose">
      {getModelSpec(type.modelKey) && <ModelThumbnail modelKey={type.modelKey} large />}
      <p className="text-[13px] leading-snug">{type.purpose}</p>
      {size && <p className="font-mono text-[11px] text-muted">Default size {size}</p>}
      <p className="text-muted">{tileTip(type)}</p>
    </div>
  )
}

function Tile({ type, active }: { type: AssetType; active: boolean }) {
  const click = () => {
    // the click that ends a drag has already placed the asset
    if (consumeDragClick()) return
    if (active) {
      useEditorStore.getState().clearTool()
    } else if (type.placementMode === 'DRAW') {
      startDraw(type)
    } else {
      useEditorStore.getState().setTool('place', type.code)
    }
  }
  const purposeId = `palette-tile-${type.code}-purpose`
  // The card opens beside the palette, never over the next tile: offset by the room left to the palette's edge
  const [cardOffset, setCardOffset] = useState(CARD_GAP)
  const measure = (event: SyntheticEvent<HTMLElement>) => {
    const palette = event.currentTarget.closest('[data-testid="asset-palette"]')
    if (!palette) return
    const room =
      palette.getBoundingClientRect().right - event.currentTarget.getBoundingClientRect().right
    setCardOffset(Math.max(0, room) + CARD_GAP)
  }
  return (
    <li>
      <span id={purposeId} hidden>
        {type.purpose}
      </span>
      <Tooltip
        side="right"
        sideOffset={cardOffset}
        // the card sits over the map: it must never catch the click that places the asset
        passThrough
        title={type.label}
        content={<PurposeCard type={type} />}
      >
        <button
          type="button"
          aria-pressed={active}
          aria-describedby={purposeId}
          data-testid={`palette-tile-${type.code}`}
          onPointerEnter={measure}
          onFocus={measure}
          onClick={click}
          onPointerDown={(event) => {
            if (event.button === 0) beginTileDrag(type, event)
          }}
          className={cn(
            // p-1 in the 220 px palette (<= 1100 px) so the 84 px thumbnail still fits two to a row
            'flex w-full touch-none flex-col items-center gap-1 rounded-lg border border-line-soft p-1.5 text-center select-none hover:bg-paper-2 max-wide:p-1',
            active && 'border-signal bg-signal-soft',
          )}
        >
          <TypeThumbnail type={type} />
          <span className="text-[12px] leading-tight font-medium text-ink">{tileLabel(type)}</span>
        </button>
      </Tooltip>
    </li>
  )
}

function Group({
  code,
  types,
  open,
  onToggle,
  active,
}: {
  code: string
  types: AssetType[]
  open: boolean
  onToggle: () => void
  active: string | null
}) {
  const id = `palette-group-${code}`
  return (
    <section aria-label={groupLabel(code)} data-testid={id}>
      <h3>
        <button
          type="button"
          aria-expanded={open}
          aria-controls={`${id}-tiles`}
          onClick={onToggle}
          className="flex w-full items-center gap-1.5 rounded-lg px-1 py-1.5 text-left font-brand text-[12px] font-bold text-ink hover:bg-paper-2"
        >
          <ChevronRight
            aria-hidden="true"
            className={cn('size-4 shrink-0 transition-transform', open && 'rotate-90')}
          />
          <span className="flex-1">{groupLabel(code)}</span>
        </button>
      </h3>
      {open && (
        <ul id={`${id}-tiles`} className="mt-1 mb-2 grid grid-cols-2 gap-1.5">
          {types.map((t) => (
            <Tile key={t.code} type={t} active={active === t.code} />
          ))}
        </ul>
      )}
    </section>
  )
}

/** The chip with the label that follows the pointer while a tile is dragged. */
export function DragChip() {
  const type = usePlacementStore((s) => s.dragging)
  const pointer = usePlacementStore((s) => s.pointer)
  if (!type || !pointer) return null
  return (
    <div
      data-testid="drag-chip"
      className="pointer-events-none fixed z-overlay rounded-full border border-line bg-paper px-3 py-1 text-xs font-medium text-ink shadow-sm"
      style={{ left: pointer.x + 14, top: pointer.y + 14 }}
    >
      {tileLabel(type)}
    </div>
  )
}

/**
 * The asset palette (DESIGNER-04): a search box and the asset types in collapsible groups, in palette order. Only the
 * first group is open at first; a search opens every group that has a match. A tile starts the place or draw tool, a
 * drag of more than 6 px drops the asset on the 2D map or the 3D ground.
 */
export function AssetPalette() {
  const types = useAssetTypes()
  const [search, setSearch] = useState('')
  const [opened, setOpened] = useState<string[] | null>(null)
  const active = useEditorStore((s) =>
    s.tool?.kind === 'place' || s.tool?.kind === 'draw' ? s.tool.typeCode : null,
  )

  const groups = useMemo(() => {
    const byGroup = new Map<string, AssetType[]>()
    for (const t of types.data ?? [])
      byGroup.set(t.paletteGroup, [...(byGroup.get(t.paletteGroup) ?? []), t])
    return [...byGroup.entries()]
  }, [types.data])

  // a drag in progress may end outside the palette; never leave the chip behind when the palette goes away
  useEffect(() => () => usePlacementStore.getState().setDragging(null), [])

  const q = search.trim().toLowerCase()
  const shown = groups
    .map(([code, list]): [string, AssetType[]] => [
      code,
      !q || groupLabel(code).toLowerCase().includes(q)
        ? list
        : list.filter((t) => t.label.toLowerCase().includes(q)),
    ])
    .filter(([, list]) => list.length > 0)

  const isOpen = (code: string, index: number) =>
    q !== '' || (opened === null ? index === 0 : opened.includes(code))
  const toggle = (code: string) =>
    setOpened((current) => {
      const now = current ?? (groups[0] ? [groups[0][0]] : [])
      return now.includes(code) ? now.filter((c) => c !== code) : [...now, code]
    })

  return (
    <div className="mt-2 flex flex-col gap-2" data-testid="asset-palette">
      <div className="relative">
        <Search aria-hidden="true" className="absolute top-2 left-2.5 size-4 text-muted" />
        <input
          type="search"
          aria-label="Search assets"
          placeholder="Search assets"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="h-8 w-full rounded-lg border border-line bg-paper pr-2 pl-8 text-[13px] outline-none focus-visible:ring-2 focus-visible:ring-signal"
        />
      </div>
      {types.isPending ? (
        <p className="text-xs text-muted">Loading asset types…</p>
      ) : shown.length === 0 ? (
        <p className="py-6 text-center text-xs text-muted">No asset matches.</p>
      ) : (
        shown.map(([code, list]) => {
          const index = groups.findIndex(([c]) => c === code)
          return (
            <Group
              key={code}
              code={code}
              types={list}
              open={isOpen(code, index)}
              onToggle={() => toggle(code)}
              active={active}
            />
          )
        })
      )}
    </div>
  )
}
