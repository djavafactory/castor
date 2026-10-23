import { beforeEach, describe, expect, it, vi } from 'vitest'
import { basicCredential, useAuthStore } from './auth-store'
import { useUiStore } from './ui-store'

const session = {
  username: 'ada',
  displayName: 'Ada Lovelace',
  roles: ['EDITOR' as const],
  credential: basicCredential('ada', 'p@ss wörd'),
}

beforeEach(() => {
  useAuthStore.getState().clearSession()
  localStorage.clear()
  sessionStorage.clear()
})

describe('useAuthStore', () => {
  it('starts signed out', () => {
    expect(useAuthStore.getState()).toMatchObject({ username: null, roles: [], credential: null })
  })

  it('signs in and clears the session', () => {
    useAuthStore.getState().signIn(session)
    expect(useAuthStore.getState()).toMatchObject({ username: 'ada', roles: ['EDITOR'] })

    useAuthStore.getState().clearSession()
    expect(useAuthStore.getState()).toMatchObject({
      username: null,
      displayName: null,
      roles: [],
      credential: null,
    })
  })

  it('builds a UTF-8 safe Basic credential', () => {
    const decoded = new TextDecoder().decode(
      Uint8Array.from(atob(session.credential.replace('Basic ', '')), (c) => c.charCodeAt(0)),
    )
    expect(session.credential.startsWith('Basic ')).toBe(true)
    expect(decoded).toBe('ada:p@ss wörd')
  })

  it('writes nothing auth-related to web storage or cookies', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    document.cookie = ''

    useAuthStore.getState().signIn(session)
    useAuthStore.getState().clearSession()
    useAuthStore.getState().signIn(session)
    // UI preferences are the only thing allowed in sessionStorage.
    useUiStore.getState().setViewMode('3d')

    const written = JSON.stringify(setItem.mock.calls)
    expect(written).not.toContain('ada')
    expect(written).not.toContain('Basic')
    expect(written).not.toContain(session.credential)
    expect(localStorage.length).toBe(0)
    expect(JSON.stringify({ ...sessionStorage })).not.toMatch(/ada|Basic|credential|roles/)
    expect(document.cookie).toBe('')

    setItem.mockRestore()
  })
})
