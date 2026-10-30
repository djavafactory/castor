import { act, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { ConnectionChip } from './connection-chip'
import { useLiveStore } from './live-store'

beforeEach(() => useLiveStore.getState().reset())

describe('ConnectionChip', () => {
  it('shows nothing before the stream starts', () => {
    const { container } = render(<ConnectionChip />)
    expect(container).toBeEmptyDOMElement()
  })

  it('says Live with the time of the last message in its accessible name', () => {
    render(<ConnectionChip />)
    act(() => {
      useLiveStore.getState().setConnection('live', 0)
      useLiveStore.getState().touch(Date.parse('2026-10-30T08:00:05Z'))
    })
    const chip = screen.getByRole('status', {
      name: /Live connection: Live\. Last message at \d\d:\d\d:\d\d/,
    })
    expect(chip).toHaveTextContent('Live')
  })

  it('says Reconnecting with the attempt, then Offline', () => {
    render(<ConnectionChip />)
    act(() => useLiveStore.getState().setConnection('reconnecting', 3))
    expect(screen.getByRole('status')).toHaveTextContent('Reconnecting... (attempt 3)')
    expect(screen.getByRole('status')).toHaveAccessibleName(/No message received yet/)

    act(() => useLiveStore.getState().setConnection('offline', 9))
    expect(screen.getByRole('status')).toHaveTextContent('Offline')
  })
})
