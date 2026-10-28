import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { describe, expect, it, vi } from 'vitest'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { layoutWire } from '@/test/fixtures'
import { wrapperFor } from '@/test/query-wrapper'
import { server } from '@/test/setup'
import { MoreMenu } from './more-menu'

vi.mock('sonner', () => ({ toast: Object.assign(vi.fn(), { error: vi.fn() }) }))

const BASE = 'http://api.test/api/v1'
const DRAFT = `${BASE}/ports/WESTPORTS/layouts/draft`
const ACCURACY_REPORT = `${BASE}/layouts/v-1/accuracy-report`

function renderMenu() {
  return render(<MoreMenu />, { wrapper: wrapperFor() })
}

beforeEach(() => {
  vi.mocked(toast).mockClear()
  vi.mocked(toast.error).mockClear()
  useUiStore.setState({
    mode: 'design',
    rightTab: 'inspector',
    helpOpen: false,
    accuracyColouring: false,
  })
  useEditorStore.getState().reset()
  server.use(
    http.get(DRAFT, () =>
      HttpResponse.json(layoutWire({ versionId: 'v-1', number: 2, status: 'DRAFT', assets: [] })),
    ),
  )
})

describe('MoreMenu: Verify accuracy (DESIGNER-11)', () => {
  it('runs the accuracy check, opens the Issues tab and logs the history entry', async () => {
    server.use(
      http.get(ACCURACY_REPORT, () =>
        HttpResponse.json({
          ranAt: '2026-10-27T14:05:00Z',
          controlPoints: [],
          maxResidualM: 0,
          maxRoundTripMm: 0,
          countsByAccuracy: { SURVEYED: 0, VERIFIED: 0, OPERATOR: 0, APPROXIMATE: 0 },
          approximateCount: 0,
          note: 'note',
        }),
      ),
    )
    renderMenu()

    await userEvent.click(screen.getByRole('button', { name: 'More' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Verify accuracy' }))

    await waitFor(() => expect(useUiStore.getState().rightTab).toBe('issues'))
    expect(useEditorStore.getState().historyLog[0]?.label).toBe('Ran the accuracy check')
    expect(useUiStore.getState().accuracyColouring).toBe(true)
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('shows an error toast and does not change the tab when the report cannot be loaded', async () => {
    server.use(http.get(ACCURACY_REPORT, () => HttpResponse.error()))
    renderMenu()

    await userEvent.click(screen.getByRole('button', { name: 'More' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Verify accuracy' }))

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('The accuracy report could not be loaded'),
    )
    expect(useUiStore.getState().rightTab).toBe('inspector')
  })
})
