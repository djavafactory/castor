import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { Logo } from '@/components/logo'
import { getEnv } from '@/lib/env'
import { basicCredential, useAuthStore } from '@/lib/stores/auth-store'
import { fetchDemoUsers, fetchMe } from '@/lib/auth-api'
import { useServerVersion, versionLine } from '@/lib/version'

export const LOGIN_ERROR = 'Wrong username or password'

export function LoginPage() {
  const env = getEnv()
  const navigate = useNavigate()
  const signIn = useAuthStore((s) => s.signIn)

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const usernameRef = useRef<HTMLInputElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)
  const submitRef = useRef<HTMLButtonElement>(null)

  useEffect(() => usernameRef.current?.focus(), [])

  // The selector needs the flag, a local or dev build (already part of the flag) and an answering endpoint.
  const demo = useQuery({
    queryKey: ['demo-users'],
    queryFn: ({ signal }) => fetchDemoUsers(signal),
    enabled: env.VITE_DEMO_LOGIN,
    retry: false,
    staleTime: Infinity,
  })
  const serverVersion = useServerVersion()
  const demoUsers = env.VITE_DEMO_LOGIN && demo.data && demo.data.length > 0 ? demo.data : null
  const selected = demoUsers?.find((u) => u.username === username)?.username ?? ''

  function pickDemoUser(name: string) {
    if (!name) return
    setUsername(name)
    setPassword(name) // the demo password is the username
    setError('')
    submitRef.current?.focus()
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    try {
      const credential = basicCredential(username.trim(), password)
      const me = await fetchMe(credential)
      signIn({ username: me.username, displayName: me.displayName, roles: me.roles, credential })
      void navigate('/', { replace: true })
    } catch {
      // Wrong password, unknown user, locked account or server trouble all look the same on purpose.
      setError(LOGIN_ERROR)
      setPassword('')
      passwordRef.current?.focus()
    } finally {
      setBusy(false)
    }
  }

  const field =
    'h-9 w-full rounded-lg border border-line bg-paper px-3 text-sm text-ink placeholder:text-muted'

  return (
    <main className="relative z-login grid h-full place-items-center bg-canvas p-4">
      <div className="w-[360px] max-w-full overflow-hidden rounded-[14px] bg-paper shadow-card">
        <form className="flex flex-col gap-3 p-6" onSubmit={submit} noValidate>
          <div className="flex items-center gap-2.5">
            <Logo />
            <span className="font-brand text-lg font-bold">Castor</span>
          </div>
          <p className="-mt-1 text-sm text-muted">Westports digital twin</p>

          {demoUsers && (
            <div className="flex flex-col gap-1">
              <label htmlFor="demo-user" className="text-xs font-medium">
                Sign in as
              </label>
              <select
                id="demo-user"
                aria-label="Choose a demo user"
                className={field}
                value={selected}
                onChange={(e) => pickDemoUser(e.target.value)}
              >
                <option value="">Choose a user...</option>
                {demoUsers.map((u) => (
                  <option key={u.username} value={u.username}>
                    {u.label}
                  </option>
                ))}
              </select>
            </div>
          )}

          <div className="flex flex-col gap-1">
            <label htmlFor="username" className="text-xs font-medium">
              Username
            </label>
            <input
              id="username"
              ref={usernameRef}
              className={field}
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="password" className="text-xs font-medium">
              Password
            </label>
            <div className="relative">
              <input
                id="password"
                ref={passwordRef}
                className={`${field} pr-14`}
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
              <button
                type="button"
                aria-pressed={showPassword}
                aria-label={showPassword ? 'Hide password' : 'Show password'}
                className="absolute inset-y-0 right-1 my-auto h-7 rounded-md px-2 text-xs font-medium text-muted hover:text-ink"
                onClick={() => setShowPassword((v) => !v)}
              >
                {showPassword ? 'Hide' : 'Show'}
              </button>
            </div>
          </div>

          <p role="alert" className="min-h-[18px] text-xs text-bad">
            {error}
          </p>

          <button
            ref={submitRef}
            type="submit"
            disabled={busy}
            className="h-9 w-full rounded-lg bg-ink text-sm font-semibold text-paper disabled:opacity-60"
          >
            Sign in
          </button>

          {demoUsers && (
            <p className="text-xs text-muted">
              Pick a user above, or type admin, editor or viewer. The password is the same as the
              username.
            </p>
          )}
        </form>
        <div className="border-t border-line-soft bg-paper px-6 py-2 text-center text-[11px] text-muted">
          {versionLine(serverVersion)}
        </div>
      </div>
    </main>
  )
}
