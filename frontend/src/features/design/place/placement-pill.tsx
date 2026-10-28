import { useEffect, useState, type RefObject } from 'react'
import { usePlacementStore, VALID_HINT } from './use-placement'

/**
 * The pill that follows the pointer while an asset is placed: the reason in red when the spot is refused, the hint
 * when it is fine. Listens on its host (the 2D map or the 3D stage) and never takes the pointer itself.
 */
export function PlacementPill({ containerRef }: { containerRef: RefObject<HTMLElement | null> }) {
  const plan = usePlacementStore((s) => s.plan)
  const problem = usePlacementStore((s) => s.problem)
  const [at, setAt] = useState<{ x: number; y: number } | null>(null)

  useEffect(() => {
    const host = containerRef.current
    if (!host) return
    const move = (event: PointerEvent) => {
      const rect = host.getBoundingClientRect()
      setAt({ x: event.clientX - rect.left, y: event.clientY - rect.top })
    }
    const leave = () => setAt(null)
    host.addEventListener('pointermove', move)
    host.addEventListener('pointerleave', leave)
    return () => {
      host.removeEventListener('pointermove', move)
      host.removeEventListener('pointerleave', leave)
    }
  }, [containerRef])

  if (!plan || !at) return null
  return (
    <div
      role="status"
      data-testid="placement-pill"
      data-valid={problem === null}
      className="pointer-events-none absolute z-overlay max-w-[320px] rounded-full border border-line-soft bg-paper px-3 py-1 text-xs shadow-sm"
      style={{ left: at.x + 16, top: at.y + 16 }}
    >
      {problem === null ? (
        <span className="text-ink">{VALID_HINT}</span>
      ) : (
        <span className="text-bad">{problem}</span>
      )}
    </div>
  )
}
