import { cn } from '@/lib/utils'

interface SegmentedProps<T extends string> {
  label: string
  value: T
  options: { value: T; label: string }[]
  onChange: (value: T) => void
  className?: string
}

/** Pill on paper-2; the active segment is ink with paper text. */
export function Segmented<T extends string>({
  label,
  value,
  options,
  onChange,
  className,
}: SegmentedProps<T>) {
  return (
    <div
      role="group"
      aria-label={label}
      className={cn(
        'inline-flex gap-0.5 rounded-full bg-paper-2 p-0.5 ring-1 ring-line-soft',
        className,
      )}
    >
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          aria-pressed={value === o.value}
          className={cn(
            'h-6 rounded-full px-3 text-xs font-medium transition-colors',
            value === o.value ? 'bg-ink text-paper' : 'text-ink hover:bg-paper',
          )}
          onClick={() => onChange(o.value)}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}
