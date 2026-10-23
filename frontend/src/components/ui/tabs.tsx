import { cn } from '@/lib/utils'

interface TabsProps<T extends string> {
  label: string
  value: T
  tabs: { value: T; label: string }[]
  onChange: (value: T) => void
  /** Set when the caller renders a matching `tabpanel-<value>` element. */
  panel?: boolean
  className?: string
}

/** 12 px / 600 muted; the active tab is ink with a 2 px signal underline. Arrow keys move between tabs. */
export function Tabs<T extends string>({
  label,
  value,
  tabs,
  onChange,
  panel,
  className,
}: TabsProps<T>) {
  return (
    <div
      role="tablist"
      aria-label={label}
      className={cn('flex gap-1 border-b border-line-soft px-3 pt-2', className)}
    >
      {tabs.map((t, i) => (
        <button
          key={t.value}
          id={`tab-${t.value}`}
          type="button"
          role="tab"
          aria-selected={value === t.value}
          aria-controls={panel ? `tabpanel-${t.value}` : undefined}
          tabIndex={value === t.value ? 0 : -1}
          className={cn(
            'h-8 border-b-2 px-3 text-xs font-semibold transition-colors',
            value === t.value
              ? 'border-signal text-ink'
              : 'border-transparent text-muted hover:text-ink',
          )}
          onClick={() => onChange(t.value)}
          onKeyDown={(e) => {
            const delta = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0
            if (!delta) return
            const next = tabs[(i + delta + tabs.length) % tabs.length]
            onChange(next.value)
            document.getElementById(`tab-${next.value}`)?.focus()
          }}
        >
          {t.label}
        </button>
      ))}
    </div>
  )
}
