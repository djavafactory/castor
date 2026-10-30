import { Link } from 'react-router'
import { Logo } from '@/components/logo'
import { usePort } from '@/lib/api'
import { canReplayDeadLetters } from '@/lib/roles'
import { useAuthStore } from '@/lib/stores/auth-store'
import { DeadLetters } from './dead-letters'
import { ReviewItems } from './review-items'

/**
 * Data quality: one place for what the live state could not settle alone. Review items (stale events, source conflicts,
 * balances below zero or over capacity) are resolved with a note by an OPERATOR or an ADMIN; dead letters (events the
 * projector gave up on) are replayed by an ADMIN. The route is closed to everyone else.
 */
export function DataQualityPage() {
  const roles = useAuthStore((s) => s.roles)
  const username = useAuthStore((s) => s.username)
  const port = usePort()

  return (
    <div className="grid h-full grid-rows-[56px_minmax(0,1fr)] overflow-hidden bg-paper">
      <header className="flex items-center gap-3 border-b border-line-soft bg-paper px-4">
        <div className="flex items-center gap-2.5">
          <Logo />
          <span className="font-brand text-lg font-bold">Castor</span>
        </div>
        <span aria-hidden="true" className="h-[18px] w-px bg-line" />
        <span className="text-sm font-medium">{port.data?.name ?? 'Port'}</span>
        <nav aria-label="Pages" className="ml-2 flex gap-1">
          <Link to="/" className="rounded-full px-3 py-1 text-[13px] font-medium hover:bg-paper-2">
            Monitor
          </Link>
          <Link
            to="/integrations"
            className="rounded-full px-3 py-1 text-[13px] font-medium hover:bg-paper-2"
          >
            Integrations
          </Link>
          <Link
            to="/data-quality"
            aria-current="page"
            className="rounded-full bg-ink px-3 py-1 text-[13px] font-medium text-paper"
          >
            Data quality
          </Link>
        </nav>
        <span className="ml-auto text-sm font-medium">{username}</span>
      </header>
      <main className="min-h-0 overflow-auto p-4" aria-label="Data quality">
        <div className="mx-auto flex max-w-6xl flex-col gap-8">
          <ReviewItems />
          <DeadLetters canReplay={canReplayDeadLetters(roles)} />
        </div>
      </main>
    </div>
  )
}
