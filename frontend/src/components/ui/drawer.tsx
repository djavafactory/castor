import { X } from 'lucide-react'
import { useEffect, useRef, type ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { IconButton } from './button'

/**
 * A panel that slides in from the right edge over the page: a title, a close button and a scrolling body. Not modal: the
 * page behind stays usable. Esc closes it (unless the key is meant for a dialog opened from inside it) and the focus
 * goes to it when it opens and back to where it was when it closes.
 */
export function Drawer({
  open,
  title,
  onClose,
  children,
  className,
}: {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
  className?: string
}) {
  const ref = useRef<HTMLElement>(null)

  useEffect(() => {
    if (!open) return
    const before = document.activeElement
    ref.current?.focus()
    return () => {
      if (before instanceof HTMLElement && document.contains(before)) before.focus()
    }
  }, [open])

  if (!open) return null
  return (
    <aside
      ref={ref}
      role="dialog"
      aria-label={title}
      tabIndex={-1}
      onKeyDown={(event) => {
        // A dialog opened from the drawer lives in a portal but its keys bubble here: only our own Esc closes us
        if (event.key === 'Escape' && ref.current?.contains(event.target as Node)) onClose()
      }}
      className={cn(
        'fixed inset-y-0 right-0 z-overlay flex w-[min(560px,100vw)] flex-col border-l border-line bg-paper shadow-popover outline-none',
        className,
      )}
    >
      <div className="flex items-center gap-2 border-b border-line-soft px-4 py-2.5">
        <h2 className="min-w-0 flex-1 truncate text-sm font-bold">{title}</h2>
        <IconButton label="Close" tip="Close (Esc)" onClick={onClose}>
          <X aria-hidden="true" />
        </IconButton>
      </div>
      <div className="min-h-0 flex-1 overflow-auto p-4">{children}</div>
    </aside>
  )
}
