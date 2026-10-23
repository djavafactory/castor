import { cva, type VariantProps } from 'class-variance-authority'
import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

const badgeVariants = cva(
  'inline-flex items-center rounded-full border px-2 py-px text-[10px] font-bold tracking-[0.06em] uppercase',
  {
    variants: {
      variant: {
        neutral: 'border-line bg-paper-2 text-ink',
        synthetic: 'border-synthetic-bd bg-synthetic-bg text-synthetic-fg',
        ok: 'border-ok bg-paper text-ink',
        warn: 'border-warn bg-warn-soft text-warn-soft-fg',
        bad: 'border-bad bg-bad-soft text-ink',
      },
    },
    defaultVariants: { variant: 'neutral' },
  },
)

export function Badge({
  className,
  variant,
  ...props
}: ComponentProps<'span'> & VariantProps<typeof badgeVariants>) {
  return <span className={cn(badgeVariants({ variant }), className)} {...props} />
}

export const NoSurveyBadge = () => <Badge variant="warn">No survey</Badge>
export const LockedBadge = () => <Badge variant="neutral">Locked</Badge>

/** Small round count (issues, warnings). */
export function CountPill({
  tone,
  className,
  ...props
}: ComponentProps<'span'> & { tone: 'bad' | 'warn' }) {
  return (
    <span
      className={cn(
        'inline-flex min-w-[18px] items-center justify-center rounded-full px-1.5 text-[10px] font-bold',
        tone === 'bad' ? 'bg-bad text-on-signal' : 'bg-warn text-ink',
        className,
      )}
      {...props}
    />
  )
}

export function Tag({ className, ...props }: ComponentProps<'span'>) {
  return (
    <span
      className={cn(
        'inline-flex rounded-full bg-tag-bg px-2 py-px text-[10px] text-ink',
        className,
      )}
      {...props}
    />
  )
}

const DOT = {
  ok: 'bg-ok',
  warn: 'bg-warn',
  bad: 'bg-bad',
  info: 'bg-info',
  off: 'bg-off',
} as const

export function StatusDot({
  status,
  label,
  className,
}: {
  status: keyof typeof DOT
  /** Accessible text; the colour alone never carries the status. */
  label: string
  className?: string
}) {
  return (
    <span
      role="img"
      aria-label={label}
      title={label}
      className={cn('inline-block size-2 shrink-0 rounded-full', DOT[status], className)}
    />
  )
}
