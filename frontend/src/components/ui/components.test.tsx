import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { Badge, CountPill, LockedBadge, NoSurveyBadge, StatusDot, Tag } from './badge'
import { Button, IconButton } from './button'
import { DataTable, FloatingCard, KeyValue, ProgressBar } from './card'
import { Input } from './field'
import { ConfirmDialog, Menu, MenuContent, MenuItem, MenuTrigger } from './overlays'
import { Segmented } from './segmented'
import { Tabs } from './tabs'

describe('Button', () => {
  it.each([
    ['default', 'bg-paper'],
    ['primary', 'bg-ink'],
    ['danger', 'text-bad'],
    ['ghost', 'bg-transparent'],
  ] as const)('renders the %s variant', (variant, cls) => {
    render(<Button variant={variant}>Go</Button>)
    expect(screen.getByRole('button', { name: 'Go' })).toHaveClass(cls, 'rounded-full')
  })

  it('is a small pill and dims when disabled', () => {
    render(
      <Button size="sm" disabled>
        Go
      </Button>,
    )
    expect(screen.getByRole('button')).toHaveClass('px-2.5', 'disabled:opacity-40')
    expect(screen.getByRole('button')).toBeDisabled()
  })

  it('takes keyboard focus and activates with Enter and Space', async () => {
    const onClick = vi.fn()
    render(<Button onClick={onClick}>Go</Button>)
    await userEvent.tab()
    expect(screen.getByRole('button')).toHaveFocus()
    await userEvent.keyboard('{Enter} ')
    expect(onClick).toHaveBeenCalledTimes(2)
  })
})

describe('IconButton', () => {
  it('is named, and pressed switches to the signal style', () => {
    const { rerender } = render(<IconButton label="Layers" />)
    expect(screen.getByRole('button', { name: 'Layers' })).not.toHaveClass('bg-signal-soft')
    rerender(<IconButton label="Layers" pressed />)
    const button = screen.getByRole('button', { name: 'Layers' })
    expect(button).toHaveAttribute('aria-pressed', 'true')
    expect(button).toHaveClass('bg-signal-soft', 'text-signal', 'border-signal')
  })
})

describe('Segmented', () => {
  it('marks the active segment and changes on keyboard', async () => {
    const onChange = vi.fn()
    render(
      <Segmented
        label="View"
        value="2d"
        onChange={onChange}
        options={[
          { value: '2d', label: '2D' },
          { value: '3d', label: '3D' },
        ]}
      />,
    )
    expect(screen.getByRole('button', { name: '2D' })).toHaveAttribute('aria-pressed', 'true')
    await userEvent.tab()
    await userEvent.tab()
    expect(screen.getByRole('button', { name: '3D' })).toHaveFocus()
    await userEvent.keyboard('{Enter}')
    expect(onChange).toHaveBeenCalledWith('3d')
  })
})

describe('Badge, pills and dots', () => {
  it('renders every badge variant', () => {
    render(
      <>
        <Badge>Neutral</Badge>
        <Badge variant="synthetic">Synthetic</Badge>
        <Badge variant="ok">Ok</Badge>
        <Badge variant="warn">Warn</Badge>
        <Badge variant="bad">Bad</Badge>
        <NoSurveyBadge />
        <LockedBadge />
      </>,
    )
    expect(screen.getByText('Synthetic')).toHaveClass('bg-synthetic-bg', 'uppercase')
    expect(screen.getByText('Bad')).toHaveClass('border-bad')
    expect(screen.getByText('No survey')).toHaveClass('bg-warn-soft')
    expect(screen.getByText('Locked')).toBeInTheDocument()
  })

  it('renders count pills, tags and named status dots', () => {
    render(
      <>
        <CountPill tone="bad">3</CountPill>
        <CountPill tone="warn">7</CountPill>
        <Tag>crane</Tag>
        <StatusDot status="ok" label="Online" />
      </>,
    )
    expect(screen.getByText('3')).toHaveClass('bg-bad')
    expect(screen.getByText('7')).toHaveClass('bg-warn')
    expect(screen.getByText('crane')).toHaveClass('bg-tag-bg')
    expect(screen.getByRole('img', { name: 'Online' })).toHaveClass('size-2', 'bg-ok')
  })
})

describe('Tabs', () => {
  function Harness() {
    const [value, setValue] = useState('a')
    return (
      <Tabs
        label="Panel"
        value={value}
        onChange={setValue}
        tabs={[
          { value: 'a', label: 'Alpha' },
          { value: 'b', label: 'Beta' },
        ]}
      />
    )
  }

  it('underlines the active tab and moves with the arrow keys', async () => {
    render(<Harness />)
    expect(screen.getByRole('tab', { name: 'Alpha' })).toHaveClass('border-signal')
    await userEvent.tab()
    expect(screen.getByRole('tab', { name: 'Alpha' })).toHaveFocus()
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Beta' })).toHaveFocus()
    expect(screen.getByRole('tab', { name: 'Beta' })).toHaveAttribute('aria-selected', 'true')
  })
})

describe('data components', () => {
  it('aligns numeric columns right and renders key-values in mono', () => {
    render(
      <>
        <DataTable
          caption="Cranes"
          rows={[{ id: 'QC-01', reach: 38.5 }]}
          rowKey={(r) => r.id}
          columns={[
            { key: 'id', header: 'Id', render: (r) => r.id },
            { key: 'reach', header: 'Reach', numeric: true, render: (r) => r.reach },
          ]}
        />
        <KeyValue items={[['Id', 'QC-01']]} />
        <FloatingCard>Readout</FloatingCard>
      </>,
    )
    expect(screen.getByRole('columnheader', { name: 'Reach' })).toHaveClass('text-right')
    expect(screen.getByRole('cell', { name: '38.5' })).toHaveClass('text-right', 'font-mono')
    expect(screen.getByText('QC-01', { selector: 'dd' })).toHaveClass('font-mono')
    expect(screen.getByText('Readout')).toHaveClass('backdrop-blur-xs', 'bg-paper/95')
  })

  it('turns the progress fill to warn at 90 % and above', () => {
    const { rerender } = render(<ProgressBar label="Cap" value={89} />)
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '89')
    expect(screen.getByRole('progressbar').firstElementChild).toHaveClass('bg-signal')
    rerender(<ProgressBar label="Cap" value={90} />)
    expect(screen.getByRole('progressbar').firstElementChild).toHaveClass('bg-warn')
  })

  it('marks an invalid input with the bad border style', () => {
    render(<Input aria-label="Name" aria-invalid="true" />)
    expect(screen.getByLabelText('Name')).toHaveClass('aria-[invalid=true]:border-bad')
  })
})

describe('overlays', () => {
  it('opens a menu from the keyboard and picks an item', async () => {
    const onSelect = vi.fn()
    render(
      <Menu>
        <MenuTrigger>Open</MenuTrigger>
        <MenuContent>
          <MenuItem onSelect={onSelect}>Rename</MenuItem>
        </MenuContent>
      </Menu>,
    )
    await userEvent.tab()
    await userEvent.keyboard('{Enter}')
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Rename' }))
    expect(onSelect).toHaveBeenCalled()
  })

  it('ConfirmDialog confirms, and Esc cancels', async () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(
      <ConfirmDialog
        open
        title="Discard?"
        description="Edits are lost."
        confirmLabel="Discard"
        danger
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    )
    expect(screen.getByRole('alertdialog', { name: 'Discard?' })).toHaveTextContent(
      'Edits are lost.',
    )
    await userEvent.keyboard('{Escape}')
    expect(onCancel).toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Discard' }))
    expect(onConfirm).toHaveBeenCalled()
  })
})
