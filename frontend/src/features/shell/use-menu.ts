import { useCallback, useEffect, useRef } from 'react'
import { useUiStore } from '@/lib/stores/ui-store'

/**
 * One open menu at a time (header and strip share the slot in the ui store). The menu closes on an outside
 * click and on Esc, and Esc returns focus to the trigger.
 */
export function useMenu(id: string) {
  const open = useUiStore((s) => s.openMenu === id)
  const setOpenMenu = useUiStore((s) => s.setOpenMenu)
  const containerRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)

  const close = useCallback(() => {
    if (useUiStore.getState().openMenu === id) setOpenMenu(null)
  }, [id, setOpenMenu])

  const toggle = useCallback(() => {
    setOpenMenu(useUiStore.getState().openMenu === id ? null : id)
  }, [id, setOpenMenu])

  useEffect(() => {
    if (!open) return
    function onPointerDown(event: PointerEvent) {
      if (!containerRef.current?.contains(event.target as Node)) close()
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        close()
        triggerRef.current?.focus()
      }
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open, close])

  return { open, toggle, close, containerRef, triggerRef }
}
