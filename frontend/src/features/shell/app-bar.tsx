import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router'
import { toast } from 'sonner'
import { reportLogout } from '@/lib/auth-api'
import { canDesign, canViewIntegrations } from '@/lib/roles'
import { useAuthStore, type Role } from '@/lib/stores/auth-store'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore, type Mode } from '@/lib/stores/ui-store'
import { confirmLeaveDesign, MONITOR_RELOADED_MESSAGE, NOT_AN_EDITOR_MESSAGE } from './leave-design'
import { Badge } from '@/components/ui/badge'
import { ConnectionChip, stopLiveStream } from '@/features/live'
import { enterDesign } from '@/features/design/enter-design'
import { LayoutStatusBadge } from '@/features/design/layout-status-badge'
import { ApiError } from '@/lib/api-client'
import { getEnv } from '@/lib/env'
import { Logo } from '@/components/logo'
import { SYNTHETIC_EXPLANATION } from './help-content'
import { PublishedVersion } from './published-version'
import { usePort } from './use-port'
import { useMenu } from './use-menu'

const ROLE_ORDER: Role[] = ['ADMIN', 'OPERATOR', 'PUBLISHER', 'EDITOR', 'VIEWER']
const SYNTHETIC_TIP = SYNTHETIC_EXPLANATION

export function AppBar() {
  const port = usePort()
  const mode = useUiStore((s) => s.mode)
  return (
    <header className="relative z-appbar flex items-center gap-3 border-b border-line-soft bg-paper px-4">
      <div className="flex items-center gap-2.5">
        <Logo />
        <span className="font-brand text-lg font-bold">Castor</span>
      </div>
      <span aria-hidden="true" className="h-[18px] w-px bg-line" />
      <span className="text-sm font-medium">{port.name}</span>
      {mode === 'monitor' && <PublishedVersion />}
      {port.synthetic && (
        // Below 1024 px the room goes to the mode switch and the layout status
        <Badge variant="synthetic" tabIndex={0} title={SYNTHETIC_TIP} className="max-lg:hidden">
          SYNTHETIC
        </Badge>
      )}
      <ModeSwitch />
      <IntegrationsLink />
      <LayoutStatusBadge />
      <div className="ml-auto flex items-center gap-3">
        {mode === 'monitor' && <ConnectionChip />}
        <UserMenu />
      </div>
    </header>
  )
}

/** The Integrations page (sources, quarantine, mappings): OPERATOR and ADMIN only. */
function IntegrationsLink() {
  const roles = useAuthStore((s) => s.roles)
  if (!canViewIntegrations(roles)) return null
  return (
    <Link
      to="/integrations"
      className="rounded-full px-3 py-1 text-[13px] font-medium hover:bg-paper-2"
    >
      Integrations
    </Link>
  )
}

function ModeSwitch() {
  const mode = useUiStore((s) => s.mode)
  const setMode = useUiStore((s) => s.setMode)
  const roles = useAuthStore((s) => s.roles)
  const queryClient = useQueryClient()

  async function choose(next: Mode) {
    if (next === mode) return
    if (next === 'design') {
      if (!canDesign(roles)) {
        toast(NOT_AN_EDITOR_MESSAGE)
        return
      }
      setMode('design')
      try {
        await enterDesign(queryClient, getEnv().VITE_PORT_CODE)
      } catch (error) {
        setMode('monitor')
        toast.error(
          error instanceof ApiError && error.status === 403
            ? NOT_AN_EDITOR_MESSAGE
            : `Could not open the draft: ${error instanceof Error ? error.message : 'unknown error'}`,
        )
      }
      return
    }
    if (!(await confirmLeaveDesign())) return
    setMode('monitor')
    // Monitor shows the published layout: drop whatever Design loaded and fetch it again.
    await queryClient.invalidateQueries({ queryKey: ['layout'] })
    toast(MONITOR_RELOADED_MESSAGE)
  }

  const base = 'h-6 rounded-full px-3 text-[13px] font-medium'
  const on = 'bg-ink text-paper'
  const off = 'text-ink hover:bg-paper'
  return (
    <div
      role="group"
      aria-label="Mode"
      className="ml-2 inline-flex gap-0.5 rounded-full bg-paper-2 p-0.5 ring-1 ring-line-soft"
    >
      <button
        type="button"
        aria-pressed={mode === 'monitor'}
        className={`${base} ${mode === 'monitor' ? on : off}`}
        onClick={() => void choose('monitor')}
      >
        Monitor
      </button>
      <button
        type="button"
        aria-pressed={mode === 'design'}
        title="Port layout designer (layout editors only)"
        className={`${base} ${mode === 'design' ? on : off}`}
        onClick={() => void choose('design')}
      >
        Design
      </button>
    </div>
  )
}

function UserMenu() {
  const { username, displayName, roles } = useAuthStore()
  const { open, toggle, containerRef, triggerRef } = useMenu('user')
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const topRole = ROLE_ORDER.find((r) => roles.includes(r))

  function signOut() {
    void reportLogout() // reads the credential before it is cleared below
    stopLiveStream()
    useAuthStore.getState().clearSession()
    useEditorStore.getState().reset()
    useSelectionStore.getState().clear()
    useUiStore.setState({ mode: 'monitor', openMenu: null })
    queryClient.clear()
    void navigate('/login', { replace: true })
  }

  return (
    <div ref={containerRef} className="relative">
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="dialog"
        aria-controls={open ? 'user-menu' : undefined}
        aria-expanded={open}
        className="flex h-8 items-center gap-1.5 rounded-lg px-2 text-sm font-medium hover:bg-paper-2"
        onClick={toggle}
      >
        {username}
        <svg
          aria-hidden="true"
          viewBox="0 0 12 12"
          className="size-3"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.6"
        >
          <path d="m3 4.5 3 3 3-3" />
        </svg>
      </button>
      {open && (
        // A small panel (name, role, Sign out), not a list of menu items: a labelled non-modal dialog
        <div
          id="user-menu"
          role="dialog"
          aria-label="User menu"
          className="absolute top-full right-0 z-menu mt-1 w-56 rounded-xl bg-paper p-3 shadow-popover ring-1 ring-line-soft"
        >
          <p className="text-sm font-semibold">{username}</p>
          {displayName && displayName !== username && (
            <p className="text-xs text-muted">{displayName}</p>
          )}
          {topRole && (
            <span className="mt-1.5 inline-block rounded-full bg-paper-2 px-2 py-0.5 text-[10px] font-semibold tracking-wide ring-1 ring-line">
              {topRole}
            </span>
          )}
          <button
            type="button"
            className="mt-3 h-8 w-full rounded-lg border border-line text-sm font-medium hover:bg-paper-2"
            onClick={signOut}
          >
            Sign out
          </button>
        </div>
      )}
    </div>
  )
}
