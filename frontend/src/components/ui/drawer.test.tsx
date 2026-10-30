import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { Drawer } from './drawer'

describe('Drawer', () => {
  it('shows nothing while it is closed', () => {
    render(
      <Drawer open={false} title="Details" onClose={() => {}}>
        body
      </Drawer>,
    )

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('is a labelled dialog with a title, a body and a Close button', async () => {
    const onClose = vi.fn()
    render(
      <Drawer open title="Details" onClose={onClose}>
        <p>the body</p>
      </Drawer>,
    )

    const dialog = screen.getByRole('dialog', { name: 'Details' })
    expect(dialog).toHaveTextContent('the body')
    expect(dialog).toHaveFocus()
    await userEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('closes on Esc from inside, and gives the focus back to what had it', async () => {
    function Page() {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button onClick={() => setOpen(true)}>Open</button>
          <Drawer open={open} title="Details" onClose={() => setOpen(false)}>
            <input aria-label="Name" />
          </Drawer>
        </>
      )
    }
    render(<Page />)
    const opener = screen.getByRole('button', { name: 'Open' })
    await userEvent.click(opener)
    await userEvent.click(screen.getByLabelText('Name'))

    await userEvent.keyboard('{Escape}')

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(opener).toHaveFocus()
  })
})
