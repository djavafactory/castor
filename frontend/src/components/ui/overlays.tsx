import * as AlertDialog from '@radix-ui/react-alert-dialog'
import * as DropdownMenu from '@radix-ui/react-dropdown-menu'
import * as PopoverPrimitive from '@radix-ui/react-popover'
import * as TooltipPrimitive from '@radix-ui/react-tooltip'
import type { ComponentProps, ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { Button } from './button'

export const TooltipProvider = TooltipPrimitive.Provider

/** Paper, 1 px signal border, radius 12; the title is signal 13 px. */
export function Tooltip({
  title,
  children,
  content,
  side,
  sideOffset = 6,
  passThrough = false,
}: {
  title?: string
  content: ReactNode
  children: ReactNode
  side?: ComponentProps<typeof TooltipPrimitive.Content>['side']
  sideOffset?: number
  /** The tooltip never takes the pointer: it closes when the pointer leaves the trigger and clicks go through it. */
  passThrough?: boolean
}) {
  return (
    <TooltipPrimitive.Root disableHoverableContent={passThrough}>
      <TooltipPrimitive.Trigger asChild>{children}</TooltipPrimitive.Trigger>
      <TooltipPrimitive.Portal>
        <TooltipPrimitive.Content
          side={side}
          sideOffset={sideOffset}
          className={cn(
            'z-popover max-w-64 rounded-xl border border-signal bg-paper px-3 py-2 text-xs text-ink shadow-popover',
            passThrough && 'pointer-events-none',
          )}
        >
          {title && <p className="text-[13px] font-semibold text-signal">{title}</p>}
          {content}
        </TooltipPrimitive.Content>
      </TooltipPrimitive.Portal>
    </TooltipPrimitive.Root>
  )
}

export const Popover = PopoverPrimitive.Root
export const PopoverTrigger = PopoverPrimitive.Trigger

export function PopoverContent({
  heading,
  className,
  children,
  ...props
}: ComponentProps<typeof PopoverPrimitive.Content> & { heading?: string }) {
  return (
    <PopoverPrimitive.Portal>
      <PopoverPrimitive.Content
        sideOffset={6}
        className={cn(
          'z-popover w-64 rounded-xl border border-line bg-paper p-3 text-[13px] shadow-popover',
          className,
        )}
        {...props}
      >
        {heading && (
          <h3 className="mb-2 text-[11px] font-bold tracking-[0.07em] text-signal uppercase">
            {heading}
          </h3>
        )}
        {children}
      </PopoverPrimitive.Content>
    </PopoverPrimitive.Portal>
  )
}

export const Menu = DropdownMenu.Root
export const MenuTrigger = DropdownMenu.Trigger

export function MenuContent({ className, ...props }: ComponentProps<typeof DropdownMenu.Content>) {
  return (
    <DropdownMenu.Portal>
      <DropdownMenu.Content
        sideOffset={6}
        className={cn(
          'z-menu min-w-44 rounded-xl border border-line bg-paper p-1 shadow-popover',
          className,
        )}
        {...props}
      />
    </DropdownMenu.Portal>
  )
}

export function MenuItem({ className, ...props }: ComponentProps<typeof DropdownMenu.Item>) {
  return (
    <DropdownMenu.Item
      className={cn(
        'flex cursor-default items-center gap-2 rounded-md px-2 py-[7px] text-[13px] outline-none data-[disabled]:opacity-40 data-[highlighted]:bg-paper-2',
        className,
      )}
      {...props}
    />
  )
}

interface ConfirmDialogProps {
  open: boolean
  title: string
  description: ReactNode
  confirmLabel?: string
  cancelLabel?: string
  danger?: boolean
  onConfirm: () => void
  onCancel: () => void
}

/** Replaces the browser confirm(). Esc and the cancel button cancel. */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  danger,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  return (
    <AlertDialog.Root open={open} onOpenChange={(o) => !o && onCancel()}>
      <AlertDialog.Portal>
        <AlertDialog.Overlay className="fixed inset-0 z-menu bg-ink/30" />
        <AlertDialog.Content className="fixed top-1/2 left-1/2 z-menu w-[360px] max-w-[calc(100vw-2rem)] -translate-x-1/2 -translate-y-1/2 rounded-xl border border-line bg-paper p-4 shadow-popover">
          <AlertDialog.Title className="text-base font-bold">{title}</AlertDialog.Title>
          <AlertDialog.Description className="mt-1.5 text-[13px] text-muted">
            {description}
          </AlertDialog.Description>
          <div className="mt-4 flex justify-end gap-2">
            <AlertDialog.Cancel asChild>
              <Button>{cancelLabel}</Button>
            </AlertDialog.Cancel>
            <AlertDialog.Action asChild>
              <Button variant={danger ? 'danger' : 'primary'} onClick={onConfirm}>
                {confirmLabel}
              </Button>
            </AlertDialog.Action>
          </div>
        </AlertDialog.Content>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  )
}
