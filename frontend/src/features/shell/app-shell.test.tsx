import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { toast } from 'sonner'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { basicCredential, useAuthStore, type Role } from '@/lib/stores/auth-store'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { layoutFixture, layoutWire, portFixture } from '@/test/fixtures'
import { server } from '@/test/setup'
import { AppShell } from './app-shell'
import { MONITOR_RELOADED_MESSAGE, NOT_AN_EDITOR_MESSAGE, SAVE_FAILED_PROMPT } from './leave-design'

vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { error: vi.fn(), loading: vi.fn(() => 'id'), dismiss: vi.fn() }),
}))

const BASE = 'http://api.test/api'

function signInAs(roles: Role[], username = 'ada') {
  useAuthStore.getState().signIn({
    username,
    displayName: 'Ada Lovelace',
    roles,
    credential: basicCredential(username, 'secret'),
  })
}

function renderShell() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  server.use(http.get(`${BASE}/v1/ports/WESTPORTS`, () => HttpResponse.json(portFixture)))
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<AppShell />} />
          <Route path="/login" element={<p>login page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.mocked(toast).mockClear()
  useAuthStore.getState().clearSession()
  useEditorStore.getState().reset()
  useUiStore.setState({ mode: 'monitor', openMenu: null, rightTab: 'details' })
})

describe('app shell', () => {
  it('shows the port, the SYNTHETIC badge, the toolbar and the Monitor panel tabs', async () => {
    signInAs(['VIEWER'])
    renderShell()

    expect(await screen.findByText('SYNTHETIC')).toHaveAttribute(
      'title',
      'Synthetic layout: positions are placeholders, not a survey of Westports',
    )
    expect(screen.getByText('Westports')).toBeInTheDocument()
    expect(screen.getByRole('toolbar', { name: 'Tools' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Mode' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Monitor' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Details', 'Catalogue'])
  })

  it('tells a viewer that Design is for editors and stays in Monitor', async () => {
    signInAs(['VIEWER'])
    renderShell()

    await userEvent.click(screen.getByRole('button', { name: 'Design' }))

    expect(toast).toHaveBeenCalledWith(NOT_AN_EDITOR_MESSAGE)
    expect(screen.getByRole('button', { name: 'Monitor' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('switches an editor to Design with its panel tabs, without leaving the page', async () => {
    signInAs(['EDITOR', 'VIEWER'])
    server.use(
      http.get(`${BASE}/v1/ports/WESTPORTS/layouts/draft`, () => HttpResponse.json(layoutWire())),
    )
    renderShell()

    await userEvent.click(screen.getByRole('button', { name: 'Design' }))

    expect(screen.getByRole('button', { name: 'Design' })).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() =>
      expect(toast).toHaveBeenCalledWith("Design mode: editing your team's draft (based on v1)."),
    )
    expect(await screen.findByText('Draft (based on v1)')).toHaveAttribute(
      'title',
      'State of the layout you are editing',
    )
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual([
      'Inspector',
      'Issues',
      'Versions',
    ])
    expect(screen.getByLabelText('Asset palette')).toBeInTheDocument()
  })

  it('leaves Design without a question when nothing is pending, then reloads Monitor', async () => {
    signInAs(['EDITOR'])
    useUiStore.setState({ mode: 'design' })
    const confirm = vi.spyOn(window, 'confirm')
    renderShell()

    await userEvent.click(screen.getByRole('button', { name: 'Monitor' }))

    await waitFor(() => expect(toast).toHaveBeenCalledWith(MONITOR_RELOADED_MESSAGE))
    expect(confirm).not.toHaveBeenCalled()
    expect(useUiStore.getState().mode).toBe('monitor')
  })

  it('asks before leaving Design when a save failed, and stays when declined', async () => {
    signInAs(['EDITOR'])
    useUiStore.setState({ mode: 'design' })
    useEditorStore.getState().beginSave('a1')
    useEditorStore.getState().failSave('a1')
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderShell()

    await userEvent.click(screen.getByRole('button', { name: 'Monitor' }))

    await waitFor(() => expect(confirm).toHaveBeenCalledWith(SAVE_FAILED_PROMPT))
    expect(useUiStore.getState().mode).toBe('design')
  })

  it('waits for pending saves and shows "Saving changes..."', async () => {
    signInAs(['EDITOR'])
    useUiStore.setState({ mode: 'design' })
    useEditorStore.getState().beginSave('a1')
    const confirm = vi.spyOn(window, 'confirm')
    renderShell()

    await userEvent.click(screen.getByRole('button', { name: 'Monitor' }))
    expect(toast.loading).toHaveBeenCalledWith('Saving changes...')
    expect(useUiStore.getState().mode).toBe('design')

    useEditorStore.getState().endSave('a1')

    await waitFor(() => expect(useUiStore.getState().mode).toBe('monitor'))
    expect(confirm).not.toHaveBeenCalled()
  })

  it('opens the user menu with the role tag and closes it on Esc and outside click', async () => {
    signInAs(['EDITOR', 'VIEWER'])
    renderShell()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /ada/ }))
    expect(screen.getByLabelText('User menu')).toHaveTextContent('EDITOR')

    await user.keyboard('{Escape}')
    expect(screen.queryByLabelText('User menu')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /ada/ }))
    await user.click(document.body)
    expect(screen.queryByLabelText('User menu')).not.toBeInTheDocument()
  })

  it('signs out: clears memory and the query cache and returns to the login page', async () => {
    let logoutCalled = false
    server.use(
      http.post(`${BASE}/v1/auth/logout`, () => {
        logoutCalled = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    signInAs(['VIEWER'])
    renderShell()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /ada/ }))
    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(await screen.findByText('login page')).toBeInTheDocument()
    expect(useAuthStore.getState().credential).toBeNull()
    await waitFor(() => expect(logoutCalled).toBe(true))
  })
})

describe('layout', () => {
  it('hides and restores the right panel from the tool strip', async () => {
    signInAs(['VIEWER'])
    renderShell()
    expect(screen.getByLabelText('Right panel')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Hide panel' }))
    expect(screen.queryByLabelText('Right panel')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Hide panel' }))
    expect(screen.getByLabelText('Right panel')).toBeInTheDocument()
  })

  it('Design adds the palette and the 32 px bottom strip; the palette hides', async () => {
    signInAs(['EDITOR'])
    useUiStore.setState({ mode: 'design', panelHidden: false, paletteHidden: false })
    renderShell()
    expect(screen.getByLabelText('Status')).toHaveClass('h-8')
    expect(screen.getByLabelText('Asset palette')).toHaveClass('w-[260px]')
    await userEvent.click(screen.getByRole('button', { name: 'Hide palette' }))
    expect(screen.queryByLabelText('Asset palette')).not.toBeInTheDocument()
  })
})

describe('help popover', () => {
  it('shows the same version text as the login card, with the server version when it differs', async () => {
    server.use(
      http.get(`${BASE}/v1/version`, () =>
        HttpResponse.json({
          version: '9.9.9',
          commit: 'abc1234',
          buildTime: '2026-10-23T07:00:00Z',
          environment: 'test',
        }),
      ),
    )
    signInAs(['VIEWER'])
    renderShell()

    await userEvent.click(await screen.findByRole('button', { name: 'Help' }))

    expect(
      await screen.findByText(`Castor v${__APP_VERSION__} (server v9.9.9)`),
    ).toBeInTheDocument()
  })
})

describe('entering Design', () => {
  const DRAFT = `${BASE}/v1/ports/WESTPORTS/layouts/draft`
  const LAYOUTS = `${BASE}/v1/ports/WESTPORTS/layouts`
  const notFound = () =>
    HttpResponse.json(
      { title: 'Not found', status: 404 },
      { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
    )

  it('creates a draft from the published layout when there is none, and says so', async () => {
    signInAs(['EDITOR'])
    let created = 0
    server.use(
      http.get(DRAFT, notFound),
      http.post(LAYOUTS, async ({ request }) => {
        created += 1
        expect(await request.json()).toEqual({ basedOn: 'published' })
        return HttpResponse.json(layoutWire(layoutFixture, { versionNo: 3, basedOnVersionNo: 2 }), {
          status: 201,
        })
      }),
    )
    renderShell()

    await userEvent.click(screen.getByRole('button', { name: 'Design' }))

    await waitFor(() =>
      expect(toast).toHaveBeenCalledWith('Design mode: new draft created from published v2.'),
    )
    expect(created).toBe(1)
    expect(await screen.findByText('Draft (based on v2)')).toBeInTheDocument()
    expect(useUiStore.getState().mode).toBe('design')
  })

  it('shows "Blank draft" for a blank layout', async () => {
    signInAs(['EDITOR'])
    server.use(
      http.get(DRAFT, () =>
        HttpResponse.json(layoutWire(layoutFixture, { basedOnVersionNo: null })),
      ),
    )
    renderShell()

    await userEvent.click(screen.getByRole('button', { name: 'Design' }))

    expect(await screen.findByText('Blank draft')).toBeInTheDocument()
  })

  it('never creates a second draft when Design is entered again', async () => {
    signInAs(['EDITOR'])
    let draftExists = false
    let created = 0
    server.use(
      http.get(DRAFT, () => (draftExists ? HttpResponse.json(layoutWire()) : notFound())),
      http.post(LAYOUTS, () => {
        created += 1
        draftExists = true
        return HttpResponse.json(layoutWire(), { status: 201 })
      }),
    )
    renderShell()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Design' }))
    await user.click(screen.getByRole('button', { name: 'Monitor' }))
    await user.click(screen.getByRole('button', { name: 'Design' }))

    await waitFor(() =>
      expect(toast).toHaveBeenCalledWith("Design mode: editing your team's draft (based on v1)."),
    )
    expect(created).toBe(1)
  })

  it('takes the draft of a colleague when both create at once (409)', async () => {
    signInAs(['EDITOR'])
    let reads = 0
    server.use(
      http.get(DRAFT, () => (reads++ === 0 ? notFound() : HttpResponse.json(layoutWire()))),
      http.post(LAYOUTS, () =>
        HttpResponse.json(
          { title: 'Conflict', status: 409, detail: 'A draft already exists for this layout' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    renderShell()

    await userEvent.click(screen.getByRole('button', { name: 'Design' }))

    await waitFor(() =>
      expect(toast).toHaveBeenCalledWith("Design mode: editing your team's draft (based on v1)."),
    )
    expect(useUiStore.getState().mode).toBe('design')
  })

  it('goes back to Monitor with a message when the draft cannot be opened', async () => {
    signInAs(['EDITOR'])
    server.use(
      http.get(DRAFT, () =>
        HttpResponse.json(
          { title: 'Forbidden', status: 403 },
          { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    renderShell()

    await userEvent.click(screen.getByRole('button', { name: 'Design' }))

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith(NOT_AN_EDITOR_MESSAGE))
    expect(useUiStore.getState().mode).toBe('monitor')
  })
})

describe('layout status badge', () => {
  const DRAFT = `${BASE}/v1/ports/WESTPORTS/layouts/draft`

  it('shows Saving... while saves are pending, then Save failed with Retry', async () => {
    signInAs(['EDITOR'])
    useUiStore.setState({ mode: 'design' })
    server.use(http.get(DRAFT, () => HttpResponse.json(layoutWire())))
    renderShell()
    expect(await screen.findByText('Draft (based on v1)')).toBeInTheDocument()

    act(() => useEditorStore.getState().beginSave('a1'))
    expect(await screen.findByText('Saving...')).toBeInTheDocument()

    const retry = vi.fn()
    act(() => useEditorStore.getState().failSave('a1', retry))
    expect(await screen.findByText('Save failed')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(retry).toHaveBeenCalledOnce()
  })

  it('is not shown in Monitor', () => {
    signInAs(['EDITOR'])
    renderShell()
    expect(screen.queryByText(/Draft \(based on/)).not.toBeInTheDocument()
  })
})
