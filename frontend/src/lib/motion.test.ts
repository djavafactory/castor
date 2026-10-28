import { toast } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { camera, useCameraStore } from '@/components/scene/stage/view-state'
import { prefersReducedMotion, ROTATE_REFUSED, ROTATE_REFUSED_ID } from './motion'

vi.mock('sonner', () => ({ toast: vi.fn() }))

function reducedMotion(on: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: on && query === '(prefers-reduced-motion: reduce)',
      media: query,
    })),
  )
}

beforeEach(() => {
  vi.mocked(toast).mockClear()
  useCameraStore.setState({ autoRotate: false, command: null })
})

afterEach(() => vi.unstubAllGlobals())

describe('reduced motion', () => {
  it('follows the OS setting, and is off where matchMedia is missing', () => {
    reducedMotion(true)
    expect(prefersReducedMotion()).toBe(true)
    reducedMotion(false)
    expect(prefersReducedMotion()).toBe(false)
    vi.stubGlobal('matchMedia', undefined)
    expect(prefersReducedMotion()).toBe(false)
  })

  it('refuses auto-rotate with a toast', () => {
    reducedMotion(true)
    camera.toggleRotate()
    expect(useCameraStore.getState().autoRotate).toBe(false)
    expect(toast).toHaveBeenCalledWith(ROTATE_REFUSED, { id: ROTATE_REFUSED_ID })
    expect(ROTATE_REFUSED).toBe('Auto-rotate is off because reduced motion is on')
  })

  it('still lets a running rotation be turned off', () => {
    reducedMotion(true)
    useCameraStore.setState({ autoRotate: true })
    camera.toggleRotate()
    expect(useCameraStore.getState().autoRotate).toBe(false)
    expect(toast).not.toHaveBeenCalled()
  })

  it('rotates as before without it', () => {
    reducedMotion(false)
    camera.toggleRotate()
    expect(useCameraStore.getState().autoRotate).toBe(true)
    expect(toast).not.toHaveBeenCalled()
  })
})
