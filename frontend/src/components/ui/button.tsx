import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

const buttonVariants = cva(
  'inline-flex items-center justify-center gap-1.5 rounded-full border text-[13px] font-medium whitespace-nowrap transition-colors disabled:pointer-events-none disabled:opacity-40',
  {
    variants: {
      variant: {
        default: 'border-line bg-paper text-ink hover:border-line-strong hover:bg-paper-2',
        primary:
          'border-ink bg-ink font-semibold text-paper hover:border-ink-hover hover:bg-ink-hover',
        danger: 'border-line bg-paper text-bad hover:border-bad hover:bg-bad-soft',
        ghost: 'border-transparent bg-transparent text-ink hover:bg-paper-2',
      },
      size: {
        default: 'px-3 py-[5px]',
        sm: 'px-2.5 py-[3px] text-xs',
      },
    },
    defaultVariants: { variant: 'default', size: 'default' },
  },
)

type ButtonProps = ComponentProps<'button'> &
  VariantProps<typeof buttonVariants> & { asChild?: boolean }

export function Button({
  className,
  variant,
  size,
  asChild,
  type = 'button',
  ...props
}: ButtonProps) {
  const Comp = asChild ? Slot : 'button'
  return (
    <Comp
      {...(asChild ? {} : { type })}
      className={cn(buttonVariants({ variant, size }), className)}
      {...props}
    />
  )
}

type IconButtonProps = ComponentProps<'button'> & {
  /** Required: an icon button has no visible text. */
  label: string
  /** The tooltip, when it says more than the label. */
  tip?: string
  pressed?: boolean
}

/** 28x28, radius 8. Pressed: signal-soft background, signal border and icon. */
export function IconButton({
  className,
  label,
  tip,
  pressed,
  type = 'button',
  ...props
}: IconButtonProps) {
  return (
    <button
      type={type}
      aria-label={label}
      title={tip ?? label}
      aria-pressed={pressed}
      className={cn(
        'inline-flex size-7 items-center justify-center rounded-md border border-transparent text-ink transition-colors hover:bg-paper-2 disabled:pointer-events-none disabled:opacity-40 [&_svg]:size-4',
        pressed && 'border-signal bg-signal-soft text-signal hover:bg-signal-soft',
        className,
      )}
      {...props}
    />
  )
}
