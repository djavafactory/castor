import { useEffect, useLayoutEffect, useRef, useState } from 'react'

const GAP = 16
const MAX_WIDTH = 290

/** True while a mouse button is held (orbiting or panning): the tooltip stays out of the way. */
function useButtonHeld(): boolean {
  const [held, setHeld] = useState(false)
  useEffect(() => {
    const down = () => setHeld(true)
    const up = () => setHeld(false)
    window.addEventListener('pointerdown', down)
    window.addEventListener('pointerup', up)
    window.addEventListener('pointercancel', up)
    return () => {
      window.removeEventListener('pointerdown', down)
      window.removeEventListener('pointerup', up)
      window.removeEventListener('pointercancel', up)
    }
  }, [])
  return held
}

/** Where a box of this size goes: cursor + 16 px, flipped to the other side at the stage edges. */
export function tooltipPosition(
  cursor: { x: number; y: number },
  box: { width: number; height: number },
  stage: { width: number; height: number },
): { left: number; top: number } {
  const left =
    cursor.x + GAP + box.width > stage.width ? cursor.x - GAP - box.width : cursor.x + GAP
  const top =
    cursor.y + GAP + box.height > stage.height ? cursor.y - GAP - box.height : cursor.y + GAP
  return { left: Math.max(0, left), top: Math.max(0, top) }
}

/** Tooltip of the hovered item: one line per fact. */
export function SceneTooltip({ x, y, lines }: { x: number; y: number; lines: string[] }) {
  const ref = useRef<HTMLDivElement>(null)
  const held = useButtonHeld()
  const [pos, setPos] = useState({ left: x + GAP, top: y + GAP })
  useLayoutEffect(() => {
    const el = ref.current
    const stage = el?.offsetParent
    if (!el || !stage) return
    setPos(
      tooltipPosition(
        { x, y },
        { width: el.offsetWidth, height: el.offsetHeight },
        { width: stage.clientWidth, height: stage.clientHeight },
      ),
    )
  }, [x, y, lines])
  if (held) return null
  return (
    <div
      ref={ref}
      role="tooltip"
      className="pointer-events-none absolute z-popover rounded-lg border border-signal bg-paper px-2.5 py-1.5 text-xs text-ink shadow-popover"
      style={{ ...pos, maxWidth: MAX_WIDTH }}
    >
      {lines.map((line, i) => (
        <p key={i} className={i === 0 ? 'font-semibold' : 'text-muted'}>
          {line}
        </p>
      ))}
    </div>
  )
}
