import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

// White fill is the one literal-free exception: Tailwind's own white keeps inputs distinct from paper.
const FIELD =
  'w-full rounded-md border border-line bg-white px-2 py-[5px] text-[13px] text-ink placeholder:text-muted disabled:opacity-40 aria-[invalid=true]:border-bad'

export function Input({ className, ...props }: ComponentProps<'input'>) {
  return <input className={cn(FIELD, className)} {...props} />
}

export function Select({ className, ...props }: ComponentProps<'select'>) {
  return <select className={cn(FIELD, className)} {...props} />
}

export function Label({ className, ...props }: ComponentProps<'label'>) {
  return <label className={cn('text-xs font-semibold', className)} {...props} />
}
