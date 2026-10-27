import { Search } from 'lucide-react'
import { useId, useMemo, useState, type KeyboardEvent } from 'react'
import { useDraftLayout, useSearch, type SearchResult } from '@/lib/api'
import { hasActiveFrame } from '@/lib/geo'
import { cn } from '@/lib/utils'
import { parseCoordinates } from './coordinates'
import { goToPlace, goToResult } from './go-to-place'
import { useDebounced } from './use-debounced'

export const SEARCH_PLACEHOLDER = 'Search a place, a shape, or paste lat, lon'
const SEARCH_DEBOUNCE_MS = 200

interface Row {
  key: string
  label: string
  /** The type of the thing; none for a pasted coordinate. */
  sublabel?: string
  choose: () => void
}

function resultRow(hit: SearchResult): Row {
  return {
    key: `${hit.kind}:${hit.id}`,
    label: hit.label,
    sublabel: hit.sublabel,
    choose: () => goToResult(hit),
  }
}

/**
 * The search box on top of the 2D map: pasted "lat, lon" is read here and goes straight to the place; any other text
 * of two characters or more is looked up in the port's own data. Nothing typed is stored or sent anywhere else.
 */
export function PlaceSearch() {
  const [text, setText] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const listId = useId()
  const draft = useDraftLayout(undefined, true, { sync: true })
  const layoutId = draft.data?.versionId ?? null

  const coordinates = useMemo(
    () => (hasActiveFrame() ? parseCoordinates(text) : ({ kind: 'none' } as const)),
    [text],
  )
  const term = useDebounced(coordinates.kind === 'none' ? text.trim() : '', SEARCH_DEBOUNCE_MS)
  // The box shows what the current text asks for, never an older answer
  const search = useSearch(term === text.trim() ? term : '', undefined, layoutId)

  const rows: Row[] = useMemo(() => {
    if (coordinates.kind === 'go') {
      return [
        {
          key: 'coordinates',
          label: coordinates.label,
          choose: () => goToPlace(coordinates.lon, coordinates.lat),
        },
      ]
    }
    return coordinates.kind === 'none' ? (search.data ?? []).map(resultRow) : []
  }, [coordinates, search.data])

  const trimmed = text.trim()
  const asking = coordinates.kind === 'none' && trimmed.length >= 2
  const status =
    coordinates.kind === 'error'
      ? coordinates.message
      : !asking || rows.length > 0
        ? null
        : search.isError
          ? 'Search is not available right now.'
          : search.isFetching || term !== trimmed
            ? 'Searching...'
            : 'No places found.'
  const showList = open && (rows.length > 0 || status !== null)
  const current = Math.min(active, Math.max(0, rows.length - 1))

  function choose(row: Row | undefined) {
    if (!row) return
    row.choose()
    setOpen(false)
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Escape') {
      setOpen(false)
    } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      setOpen(true)
      if (rows.length > 0) {
        setActive((current + (event.key === 'ArrowDown' ? 1 : rows.length - 1)) % rows.length)
      }
    } else if (event.key === 'Enter') {
      event.preventDefault()
      choose(rows[current])
    }
  }

  return (
    <div
      role="search"
      aria-label="Place search"
      className="relative w-[360px] max-w-[calc(100vw-2rem)]"
    >
      <Search
        aria-hidden="true"
        className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted"
      />
      <input
        type="search"
        role="combobox"
        aria-label="Search places"
        aria-expanded={showList}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={showList && rows.length > 0 ? `${listId}-${current}` : undefined}
        aria-invalid={coordinates.kind === 'error' || undefined}
        autoComplete="off"
        spellCheck={false}
        maxLength={100}
        placeholder={SEARCH_PLACEHOLDER}
        value={text}
        className="h-9 w-full rounded-lg border border-line bg-paper pr-3 pl-8 text-[13px] text-ink shadow-card placeholder:text-muted"
        onChange={(event) => {
          setText(event.target.value)
          setActive(0)
          setOpen(true)
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onKeyDown={onKeyDown}
      />
      {showList && (
        <ul
          id={listId}
          role="listbox"
          aria-label="Search results"
          className="absolute top-full left-0 z-menu mt-1 max-h-72 w-full overflow-auto rounded-lg border border-line bg-paper py-1 shadow-popover"
        >
          {rows.map((row, i) => (
            <li
              key={row.key}
              id={`${listId}-${i}`}
              role="option"
              aria-selected={i === current}
              className={cn(
                'flex cursor-pointer items-baseline gap-2 px-3 py-1.5 text-[13px]',
                i === current && 'bg-paper-2',
              )}
              // Mouse down, not click: the box would lose focus and close the list first
              onMouseDown={(event) => {
                event.preventDefault()
                choose(row)
              }}
              onMouseEnter={() => setActive(i)}
            >
              <span className="min-w-0 flex-1 truncate">{row.label}</span>
              {row.sublabel && <span className="shrink-0 text-xs text-muted">{row.sublabel}</span>}
            </li>
          ))}
          {rows.length === 0 && status && (
            <li
              role="status"
              className={cn(
                'px-3 py-1.5 text-[13px]',
                coordinates.kind === 'error' ? 'text-bad' : 'text-muted',
              )}
            >
              {status}
            </li>
          )}
        </ul>
      )}
    </div>
  )
}
