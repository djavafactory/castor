import type { ComponentProps, ReactNode } from 'react'
import { cn } from '@/lib/utils'

export function Card({ className, ...props }: ComponentProps<'div'>) {
  return (
    <div className={cn('rounded-xl border border-line bg-paper-2 p-3', className)} {...props} />
  )
}

/** Translucent readout inside the stage (drawing readout, ghost tooltip). */
export function FloatingCard({ className, ...props }: ComponentProps<'div'>) {
  return (
    <div
      className={cn(
        'z-overlay rounded-2xl border border-line bg-paper/95 p-3 shadow-card backdrop-blur-xs',
        className,
      )}
      {...props}
    />
  )
}

export function SectionHeading({ className, ...props }: ComponentProps<'h3'>) {
  return (
    <h3
      className={cn(
        'mt-4 mb-2 font-brand text-[11px] font-bold tracking-[0.07em] text-muted uppercase',
        className,
      )}
      {...props}
    />
  )
}

/** Label / value pairs: 90 px label column, mono value. */
export function KeyValue({ items, className }: { items: [string, string][]; className?: string }) {
  return (
    <dl className={cn('grid grid-cols-[90px_1fr] gap-x-2 gap-y-1', className)}>
      {items.map(([k, v]) => (
        <div key={k} className="contents">
          <dt className="text-xs text-muted">{k}</dt>
          <dd className="m-0 font-mono text-xs break-words">{v}</dd>
        </div>
      ))}
    </dl>
  )
}

export interface Column<R> {
  key: string
  header: string
  numeric?: boolean
  render: (row: R) => ReactNode
}

/** 12 px table; numeric columns are right-aligned. */
export function DataTable<R>({
  columns,
  rows,
  rowKey,
  caption,
}: {
  columns: Column<R>[]
  rows: R[]
  rowKey: (row: R) => string
  caption: string
}) {
  return (
    <table className="w-full border-collapse text-xs">
      <caption className="sr-only">{caption}</caption>
      <thead>
        <tr>
          {columns.map((c) => (
            <th
              key={c.key}
              scope="col"
              className={cn(
                'border-b border-line px-2 py-1 font-semibold text-muted',
                c.numeric ? 'text-right' : 'text-left',
              )}
            >
              {c.header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={rowKey(r)} className="border-b border-line-soft hover:bg-paper-2">
            {columns.map((c) => (
              <td
                key={c.key}
                className={cn('px-2 py-1', c.numeric && 'text-right font-mono tabular-nums')}
              >
                {c.render(r)}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/** 6 px bar; the fill turns warn at 90 % and above. */
export function ProgressBar({
  value,
  label,
  className,
}: {
  value: number
  label: string
  className?: string
}) {
  const pct = Math.max(0, Math.min(100, value))
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={pct}
      className={cn('h-1.5 overflow-hidden rounded-full bg-bar-track', className)}
    >
      <div
        data-warn={pct >= 90}
        className={cn(
          'h-full rounded-full transition-[width]',
          pct >= 90 ? 'bg-warn' : 'bg-signal',
        )}
        style={{ width: `${pct}%` }}
      />
    </div>
  )
}
