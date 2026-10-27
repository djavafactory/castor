import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { GOOGLE_TOAST } from '@/lib/basemap'
import { basicCredential, useAuthStore } from '@/lib/stores/auth-store'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { BasemapSelect } from './basemap-select'
import { changeBasemap } from './change-basemap'

vi.mock('sonner', () => ({ toast: Object.assign(vi.fn(), { error: vi.fn() }) }))

beforeEach(() => {
  vi.mocked(toast).mockClear()
  useAuthStore.getState().clearSession()
  useEditorStore.getState().reset()
  useUiStore.setState({ mode: 'design', basemap: 'sentinel2' })
})

afterEach(() => localStorage.clear())

function signIn(username: string) {
  act(() =>
    useAuthStore.getState().signIn({
      username,
      displayName: username,
      roles: ['EDITOR'],
      credential: basicCredential(username, 'x'),
    }),
  )
}

describe('basemap select', () => {
  it('lists the three basemaps and starts on Sentinel-2 (EOX)', () => {
    render(<BasemapSelect />)
    const select = screen.getByRole('combobox', { name: 'Basemap' })
    expect(select).toHaveValue('sentinel2')
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Sentinel-2 (EOX)',
      'None (grid)',
      'Google Maps (lookup only)',
    ])
  })

  it('switches the basemap of 2D and 3D, which share the one store value', async () => {
    render(<BasemapSelect />)
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Basemap' }), 'none')
    expect(useUiStore.getState().basemap).toBe('none')
  })

  it('logs "Basemap: <label>" in the edit history, and undo brings the old one back', async () => {
    render(<BasemapSelect />)
    await userEvent.selectOptions(
      screen.getByRole('combobox', { name: 'Basemap' }),
      'Google Maps (lookup only)',
    )

    const entry = useEditorStore.getState().undoStack.at(-1)
    expect(entry?.label).toBe('Basemap: Google Maps (lookup only)')
    await act(async () => {
      await useEditorStore.getState().undo()?.undo()
    })
    expect(useUiStore.getState().basemap).toBe('sentinel2')
    await act(async () => {
      await useEditorStore.getState().redo()?.redo()
    })
    expect(useUiStore.getState().basemap).toBe('google')
  })

  it('does not log a switch to the basemap that is already on', () => {
    changeBasemap('sentinel2')
    expect(useEditorStore.getState().undoStack).toHaveLength(0)
  })
})

describe('Google Maps is a locator only', () => {
  it('refuses to start a draw tool or a placement, with the SETUP-03 toast', () => {
    useUiStore.setState({ basemap: 'google' })
    const editor = useEditorStore.getState()

    editor.setTool('draw', 'ROAD_SEGMENT')
    editor.setTool('place', 'EXCAVATOR')

    expect(useEditorStore.getState().tool).toBeNull()
    expect(toast).toHaveBeenCalledTimes(2)
    expect(toast).toHaveBeenCalledWith(GOOGLE_TOAST)
  })

  it('still measures: nothing is stored', () => {
    useUiStore.setState({ basemap: 'google' })
    useEditorStore.getState().setTool('measure')
    expect(useEditorStore.getState().tool?.kind).toBe('measure')
    expect(toast).not.toHaveBeenCalled()
  })

  it('draws and places on the other basemaps', () => {
    for (const basemap of ['sentinel2', 'none'] as const) {
      useUiStore.setState({ basemap })
      useEditorStore.getState().setTool('draw', 'ROAD_SEGMENT')
      expect(useEditorStore.getState().tool?.kind).toBe('draw')
    }
    expect(toast).not.toHaveBeenCalled()
  })

  it('drops a tool that is on when the basemap changes to Google, but keeps a measure tool', () => {
    useEditorStore.getState().setTool('place', 'EXCAVATOR')
    changeBasemap('google')
    expect(useEditorStore.getState().tool).toBeNull()

    useUiStore.setState({ basemap: 'none' })
    useEditorStore.getState().setTool('measure')
    changeBasemap('google')
    expect(useEditorStore.getState().tool?.kind).toBe('measure')
  })
})

describe('the basemap is a preference of the user', () => {
  it('is remembered for the signed-in user and comes back when they sign in again', () => {
    signIn('ada')
    useUiStore.getState().setBasemap('none')
    expect(localStorage.getItem('castor.basemap.ada')).toBe('none')

    act(() => useAuthStore.getState().clearSession())
    expect(useUiStore.getState().basemap).toBe('sentinel2')

    signIn('ada')
    expect(useUiStore.getState().basemap).toBe('none')
  })

  it('is found when the user signed in before the store existed (the shell loads after the login)', async () => {
    localStorage.setItem('castor.basemap.ada', 'none')
    signIn('ada')
    vi.resetModules()
    // A fresh copy of the stores, as the lazily loaded shell creates them after the login
    const auth = await import('@/lib/stores/auth-store')
    auth.useAuthStore.getState().signIn({
      username: 'ada',
      displayName: 'ada',
      roles: ['EDITOR'],
      credential: auth.basicCredential('ada', 'x'),
    })
    const ui = await import('@/lib/stores/ui-store')
    expect(ui.useUiStore.getState().basemap).toBe('none')
  })

  it('is not shared with another user', () => {
    signIn('ada')
    useUiStore.getState().setBasemap('google')
    act(() => useAuthStore.getState().clearSession())

    signIn('grace')
    expect(useUiStore.getState().basemap).toBe('sentinel2')
  })

  it('is no part of the layout: nothing about it is sent to the server', () => {
    signIn('ada')
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    useUiStore.getState().setBasemap('none')
    expect(fetchSpy).not.toHaveBeenCalled()
    fetchSpy.mockRestore()
  })
})
