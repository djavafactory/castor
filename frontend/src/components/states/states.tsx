import type { ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { ProgressBar } from '@/components/ui/card'
import { cn } from '@/lib/utils'

const LOAD_STEPS = [
  { label: 'Map', value: 15 },
  { label: 'Layout', value: 55 },
  { label: 'Models', value: 80 },
  { label: 'Ready', value: 100 },
] as const

export type LoadStep = (typeof LOAD_STEPS)[number]['label']

/** Centred 300 px card with a thin signal bar and the current step. */
export function LoadingCard({ subject, step }: { subject: string; step: LoadStep }) {
  const current = LOAD_STEPS.find((s) => s.label === step) ?? LOAD_STEPS[0]
  return (
    <div className="absolute inset-0 z-menu grid place-items-center">
      <div
        role="status"
        aria-live="polite"
        className="w-[300px] rounded-xl border border-line bg-paper p-4 shadow-card"
      >
        <p className="text-sm font-semibold">Loading {subject}...</p>
        <ProgressBar value={current.value} label={`Loading ${subject}`} className="mt-3 h-1" />
        <p className="mt-2 text-xs text-muted">
          {current.label} {current.value}%
        </p>
      </div>
    </div>
  )
}

export function SkeletonRows({ rows = 4 }: { rows?: number }) {
  return (
    <div aria-hidden="true" data-testid="skeleton" className="flex flex-col gap-2 p-3">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="h-4 animate-pulse rounded-md bg-bar-track" />
      ))}
    </div>
  )
}

export function EmptyState({ children, className }: { children: ReactNode; className?: string }) {
  return <p className={cn('p-6 text-center text-xs text-muted', className)}>{children}</p>
}

/** Problem Details title and detail with a Retry button. */
export function ErrorCard({
  title,
  detail,
  onRetry,
}: {
  title: string
  detail?: string
  onRetry: () => void
}) {
  return (
    <div role="alert" className="rounded-xl border border-bad bg-paper p-3">
      <p className="text-sm font-semibold text-bad">{title}</p>
      {detail && <p className="mt-1 text-xs text-muted">{detail}</p>}
      <Button size="sm" className="mt-3" onClick={onRetry}>
        Retry
      </Button>
    </div>
  )
}

export type SaveState =
  { status: 'saving' } | { status: 'saved' } | { status: 'failed'; reason: string }

export function SaveStatus({ state, onRetry }: { state: SaveState; onRetry: () => void }) {
  return (
    <span role="status" className="inline-flex items-center gap-2 text-xs">
      {state.status === 'saving' && <span className="text-muted">Saving...</span>}
      {state.status === 'saved' && <span className="text-muted">All changes saved</span>}
      {state.status === 'failed' && (
        <>
          <span className="text-bad">Could not save: {state.reason}</span>
          <Button size="sm" variant="danger" onClick={onRetry}>
            Retry
          </Button>
        </>
      )}
    </span>
  )
}

/** Help-popover note when satellite tiles fail; no dialog. */
export function TilesNote() {
  return (
    <p className="text-xs text-muted">
      Satellite imagery could not be loaded, so the plain land colour is shown instead.
    </p>
  )
}
