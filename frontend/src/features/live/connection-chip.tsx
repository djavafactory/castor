import { Badge } from '@/components/ui/badge'
import { Tooltip, TooltipProvider } from '@/components/ui/overlays'
import { cn } from '@/lib/utils'
import { chipText, lastMessageText } from './chip-text'
import { type ConnectionStatus, useLiveStore } from './live-store'

type Look = { variant: 'ok' | 'warn' | 'bad'; dot: string }

const LOOK: Record<Exclude<ConnectionStatus, 'idle'>, Look> = {
  live: { variant: 'ok', dot: 'bg-ok' },
  connecting: { variant: 'warn', dot: 'bg-warn' },
  reconnecting: { variant: 'warn', dot: 'bg-warn' },
  offline: { variant: 'bad', dot: 'bg-bad' },
}

/** App bar chip for the live stream: Live (green), Reconnecting... (amber, attempt), Offline (red after 60 s). */
export function ConnectionChip() {
  const status = useLiveStore((s) => s.status)
  const attempt = useLiveStore((s) => s.attempt)
  const lastMessageAt = useLiveStore((s) => s.lastMessageAt)
  if (status === 'idle') return null

  const text = chipText(status, attempt)
  const tip = lastMessageText(lastMessageAt)
  const look = LOOK[status]
  return (
    <TooltipProvider delayDuration={300}>
      <Tooltip content={tip}>
        <Badge
          variant={look.variant}
          role="status"
          tabIndex={0}
          aria-label={`Live connection: ${text}. ${tip}`}
          className="gap-1.5 whitespace-nowrap"
        >
          <span aria-hidden="true" className={cn('size-1.5 rounded-full', look.dot)} />
          {text}
        </Badge>
      </Tooltip>
    </TooltipProvider>
  )
}
