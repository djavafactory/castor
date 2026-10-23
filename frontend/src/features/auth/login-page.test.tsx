import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { basicCredential, useAuthStore } from '@/lib/stores/auth-store'
import { server } from '@/test/setup'
import { LoginPage, LOGIN_ERROR } from './login-page'

const envState = vi.hoisted(() => ({ demo: false, appEnv: 'prod' }))

vi.mock('@/lib/env', () => ({
  getEnv: () => ({
    VITE_APP_ENV: envState.appEnv,
    VITE_DEMO_LOGIN: envState.demo,
    VITE_API_URL: 'http://api.test/api',
    VITE_PORT_CODE: 'westports',
  }),
}))

const BASE = 'http://api.test/api'
const DEMO_USERS = [
  { username: 'admin', label: 'Admin: full access' },
  { username: 'editor', label: 'Editor: edits layout drafts' },
  { username: 'viewer', label: 'Viewer: monitor only' },
]

function renderLogin() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/login']}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={<p>app home</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  envState.demo = false
  envState.appEnv = 'prod'
  useAuthStore.getState().clearSession()
  localStorage.clear()
  sessionStorage.clear()
})

describe('login page', () => {
  it('focuses the username and labels every field', () => {
    renderLogin()

    expect(screen.getByLabelText('Username')).toHaveFocus()
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password')
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByText('Westports digital twin')).toBeInTheDocument()
  })

  it('shows no demo selector and no demo help in a production-like build', () => {
    renderLogin()

    expect(screen.queryByLabelText('Choose a demo user')).not.toBeInTheDocument()
    expect(screen.queryByText(/Pick a user above/)).not.toBeInTheDocument()
    expect(screen.getByText(/^Castor v/)).not.toHaveTextContent('·')
  })

  it('shows the commit and environment in the footer in local and dev', () => {
    envState.appEnv = 'dev'
    renderLogin()

    expect(screen.getByText(/^Castor v/)).toHaveTextContent(/· .+ · dev$/)
  })

  it('toggles the password visibility with a labelled button', async () => {
    const user = userEvent.setup()
    renderLogin()
    const field = screen.getByLabelText('Password')

    await user.click(screen.getByRole('button', { name: 'Show password' }))

    expect(field).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', { name: 'Hide password' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })

  it('signs in with a Basic header on Enter, keeping credentials in memory only', async () => {
    let authorization: string | null = null
    server.use(
      http.get(`${BASE}/v1/me`, ({ request }) => {
        authorization = request.headers.get('authorization')
        return HttpResponse.json({ username: 'ada', displayName: 'Ada', roles: ['EDITOR'] })
      }),
    )
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByLabelText('Username'), 'ada')
    await user.type(screen.getByLabelText('Password'), 'secret{Enter}')

    expect(await screen.findByText('app home')).toBeInTheDocument()
    expect(authorization).toBe(basicCredential('ada', 'secret'))
    expect(useAuthStore.getState()).toMatchObject({ username: 'ada', roles: ['EDITOR'] })
    expect(localStorage).toHaveLength(0)
    expect(sessionStorage).toHaveLength(0)
    expect(document.cookie).toBe('')
  })

  it.each([401, 500])(
    'shows the same message, clears and focuses the password on a %i',
    async (status) => {
      server.use(http.get(`${BASE}/v1/me`, () => new HttpResponse(null, { status })))
      const user = userEvent.setup()
      renderLogin()

      await user.type(screen.getByLabelText('Username'), 'ada')
      await user.type(screen.getByLabelText('Password'), 'wrong')
      await user.click(screen.getByRole('button', { name: 'Sign in' }))

      expect(await screen.findByRole('alert')).toHaveTextContent(LOGIN_ERROR)
      expect(screen.getByLabelText('Password')).toHaveValue('')
      expect(screen.getByLabelText('Password')).toHaveFocus()
      expect(useAuthStore.getState().credential).toBeNull()
    },
  )

  describe('demo login', () => {
    beforeEach(() => {
      envState.demo = true
      envState.appEnv = 'local'
      server.use(http.get(`${BASE}/v1/auth/demo-users`, () => HttpResponse.json(DEMO_USERS)))
    })

    it('lists the demo users and fills both fields when one is picked', async () => {
      const user = userEvent.setup()
      renderLogin()

      const select = await screen.findByLabelText('Choose a demo user')
      expect(screen.getByRole('option', { name: 'Choose a user...' })).toBeInTheDocument()
      expect(screen.getByText(/Pick a user above/)).toBeInTheDocument()

      await user.selectOptions(select, 'Editor: edits layout drafts')

      expect(screen.getByLabelText('Username')).toHaveValue('editor')
      expect(screen.getByLabelText('Password')).toHaveValue('editor')
      expect(screen.getByRole('button', { name: 'Sign in' })).toHaveFocus()
    })

    it('selects the matching demo user when its name is typed', async () => {
      const user = userEvent.setup()
      renderLogin()
      const select = await screen.findByLabelText('Choose a demo user')

      await user.type(screen.getByLabelText('Username'), 'viewer')

      expect(select).toHaveValue('viewer')
    })

    it('clears the error when a demo user is picked', async () => {
      server.use(http.get(`${BASE}/v1/me`, () => new HttpResponse(null, { status: 401 })))
      const user = userEvent.setup()
      renderLogin()
      const select = await screen.findByLabelText('Choose a demo user')
      await user.type(screen.getByLabelText('Username'), 'x')
      await user.click(screen.getByRole('button', { name: 'Sign in' }))
      expect(await screen.findByRole('alert')).toHaveTextContent(LOGIN_ERROR)

      await user.selectOptions(select, 'viewer')

      await waitFor(() => expect(screen.getByRole('alert')).toBeEmptyDOMElement())
    })

    it('hides the selector when the endpoint does not answer', async () => {
      server.use(
        http.get(`${BASE}/v1/auth/demo-users`, () => new HttpResponse(null, { status: 404 })),
      )
      renderLogin()

      await waitFor(() => expect(screen.getByLabelText('Username')).toBeInTheDocument())
      expect(screen.queryByLabelText('Choose a demo user')).not.toBeInTheDocument()
      expect(screen.queryByText(/Pick a user above/)).not.toBeInTheDocument()
    })
  })
})

describe('version footer', () => {
  function serverVersion(version: string) {
    server.use(
      http.get(`${BASE}/v1/version`, () =>
        HttpResponse.json({
          version,
          commit: 'abc1234',
          buildTime: '2026-10-23T07:00:00Z',
          environment: 'prod',
        }),
      ),
    )
  }

  it('shows the build version and nothing about the server when they match', async () => {
    serverVersion(__APP_VERSION__)
    renderLogin()

    const footer = await screen.findByText(`Castor v${__APP_VERSION__}`)
    await waitFor(() => expect(footer).toHaveTextContent(/^Castor v\S+$/))
  })

  it('adds the server version when the server runs another one', async () => {
    serverVersion('9.9.9')
    renderLogin()

    expect(
      await screen.findByText(`Castor v${__APP_VERSION__} (server v9.9.9)`),
    ).toBeInTheDocument()
  })

  it('adds commit and environment in dev', async () => {
    envState.appEnv = 'dev'
    serverVersion(__APP_VERSION__)
    renderLogin()

    expect(
      await screen.findByText(`Castor v${__APP_VERSION__} · ${__GIT_COMMIT__} · dev`),
    ).toBeInTheDocument()
  })

  it('still shows the version when the server does not answer', async () => {
    server.use(http.get(`${BASE}/v1/version`, () => HttpResponse.error()))
    renderLogin()

    expect(await screen.findByText(`Castor v${__APP_VERSION__}`)).toBeInTheDocument()
  })
})
