import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { EmptyState, ErrorCard, LoadingCard, SaveStatus, SkeletonRows, TilesNote } from './states'

describe('states', () => {
  it.each([
    ['Map', '15'],
    ['Layout', '55'],
    ['Models', '80'],
    ['Ready', '100'],
  ] as const)('loading shows step %s at %s%%', (step, pct) => {
    render(<LoadingCard subject="Westports" step={step} />)
    expect(screen.getByText('Loading Westports...')).toBeInTheDocument()
    expect(screen.getByText(`${step} ${pct}%`)).toBeInTheDocument()
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', pct)
  })

  it('shows skeleton rows', () => {
    render(<SkeletonRows rows={3} />)
    expect(screen.getByTestId('skeleton').children).toHaveLength(3)
  })

  it('shows the empty text', () => {
    render(<EmptyState>Select something in the 3D view to see its details.</EmptyState>)
    expect(screen.getByText(/Select something in the 3D view/)).toHaveClass('text-muted')
  })

  it('shows the error with Retry', async () => {
    const onRetry = vi.fn()
    render(<ErrorCard title="Layout unavailable" detail="No answer." onRetry={onRetry} />)
    expect(screen.getByRole('alert')).toHaveClass('border-bad')
    expect(screen.getByText('No answer.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(onRetry).toHaveBeenCalled()
  })

  it('shows the three saving states, Retry only on failure', async () => {
    const onRetry = vi.fn()
    const { rerender } = render(<SaveStatus state={{ status: 'saving' }} onRetry={onRetry} />)
    expect(screen.getByText('Saving...')).toBeInTheDocument()
    rerender(<SaveStatus state={{ status: 'saved' }} onRetry={onRetry} />)
    expect(screen.getByText('All changes saved')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    rerender(<SaveStatus state={{ status: 'failed', reason: 'offline' }} onRetry={onRetry} />)
    expect(screen.getByText('Could not save: offline')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(onRetry).toHaveBeenCalled()
  })

  it('notes failed satellite tiles quietly', () => {
    render(<TilesNote />)
    expect(screen.getByText(/plain land colour/)).toHaveClass('text-muted')
  })
})
