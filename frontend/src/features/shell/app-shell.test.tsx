import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { toast } from 'sonner'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { basicCredential, useAuthStore, type Role } from '@/lib/stores/auth-store'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { currentLayoutFixture, portFixture } from '@/test/fixtures'
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
    renderShell()

    await userEvent.click(screen.getByRole('button', { name: 'Design' }))

    expect(screen.getByRole('button', { name: 'Design' })).toHaveAttribute('aria-pressed', 'true')
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

describe('Monitor of the published layout', () => {
  function publishedAs(versionNo: number, etag: string) {
    server.use(
      http.get(`${BASE}/v1/ports/WESTPORTS/layouts/current`, () =>
        HttpResponse.json(
          { ...currentLayoutFixture, layout: { ...currentLayoutFixture.layout, versionNo } },
          { headers: { ETag: etag } },
        ),
      ),
    )
  }

  it('shows the published version next to the port name, with the publish note as tooltip', async () => {
    publishedAs(3, '"a"')
    signInAs(['VIEWER'])
    renderShell()

    const label = await screen.findByText('Published v3 · 27 Oct 2026 14:05 by pia')
    expect(label).toHaveAttribute('title', 'Berth 4 extended')
    expect(label.previousElementSibling).toHaveTextContent('Westports')
  })

  it('is read-only and has no live data: no palette, no editing tabs, no live bar', async () => {
    publishedAs(3, '"a"')
    signInAs(['VIEWER'])
    renderShell()
    await screen.findByText(/Published v3/)

    expect(screen.queryByLabelText('Asset palette')).toBeNull()
    expect(screen.queryByRole('tab', { name: /Inspector|Issues|Versions/ })).toBeNull()
    expect(screen.queryByText(/live|KPI|schedule|connected|events/i)).toBeNull()
    expect(
      screen.getByText('Select an asset in the 3D view to read its details.'),
    ).toBeInTheDocument()
  })

  it('is the start mode and drops the version text in Design', async () => {
    publishedAs(3, '"a"')
    signInAs(['EDITOR'])
    renderShell()
    await screen.findByText(/Published v3/)
    expect(useUiStore.getState().mode).toBe('monitor')

    await userEvent.click(screen.getByRole('button', { name: 'Design' }))
    expect(screen.queryByText(/Published v3/)).toBeNull()
  })
})
