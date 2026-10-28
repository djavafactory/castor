import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { useEditorStore } from '@/lib/stores/editor-store'
import { HistoryPopover } from './history-popover'

beforeEach(() => useEditorStore.getState().reset())

describe('HistoryPopover', () => {
  it('shows "History (0)" and "No edits yet." when nothing happened', async () => {
    render(<HistoryPopover />)
    const trigger = screen.getByRole('button', { name: 'History (0)' })
    await userEvent.click(trigger)
    expect(screen.getByText('No edits yet.')).toBeInTheDocument()
  })

  it('lists entries newest first as "HH:MM <label>", up to 60', async () => {
    useEditorStore.getState().pushHistory({ label: 'Placed Excavator 1', undo() {}, redo() {} })
    useEditorStore.getState().logHistory('Basemap: None (grid)')
    render(<HistoryPopover />)
    const trigger = screen.getByRole('button', { name: 'History (2)' })
    await userEvent.click(trigger)
    const items = screen.getAllByRole('listitem').map((li) => li.textContent)
    expect(items).toHaveLength(2)
    expect(items[0]).toMatch(/^\d{2}:\d{2}Basemap: None \(grid\)$/)
    expect(items[1]).toMatch(/^\d{2}:\d{2}Placed Excavator 1$/)
  })
})
