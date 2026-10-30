import { expect, test, type WebSocketRoute } from '@playwright/test'
import { signInAsDemo } from './helpers/stack'

// STATE-02: the browser stream client on the dev stack. The stream is cut (the open socket is closed and new tickets are
// refused): the app bar chip says Reconnecting; when the ticket route is restored the chip says Live again and the client
// has fetched a fresh state snapshot. DOM roles and text and the network log only.
test.skip(!process.env.E2E_BASE_URL, 'needs the running dev stack: set E2E_BASE_URL')

test('the connection chip follows the stream: Live, Reconnecting when it is cut, Live again with a fresh snapshot', async ({
  page,
}) => {
  const snapshotRequests: string[] = []
  page.on('request', (request) => {
    if (/\/api\/v1\/ports\/[^/]+\/state\/snapshot/.test(request.url())) {
      snapshotRequests.push(request.url())
    }
  })

  // Pass the stream through, but keep the page's end so the test can close it
  const sockets: WebSocketRoute[] = []
  await page.routeWebSocket(/\/api\/v1\/ports\/[^/]+\/stream/, (ws) => {
    sockets.push(ws)
    ws.connectToServer()
  })

  await signInAsDemo(page, 'viewer')
  const chip = page.getByRole('status', { name: /Live connection/ })
  await expect(chip).toHaveText('Live', { timeout: 20_000 })
  await expect.poll(() => snapshotRequests.length).toBeGreaterThan(0)
  const before = snapshotRequests.length

  // Cut: no new tickets, and the open stream is closed
  await page.route('**/api/v1/ports/*/stream/ticket', (route) => route.abort())
  await sockets.at(-1)!.close({ code: 1011, reason: 'e2e cut' })
  await expect(chip).toContainText('Reconnecting...', { timeout: 10_000 })
  await expect(chip).toContainText(/attempt \d+/)

  // Restore: the client gets a ticket, opens the stream and fetches a fresh snapshot
  await page.unroute('**/api/v1/ports/*/stream/ticket')
  await expect(chip).toHaveText('Live', { timeout: 40_000 })
  await expect.poll(() => snapshotRequests.length).toBeGreaterThan(before)
})
