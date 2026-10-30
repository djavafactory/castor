import { expect, test, type Page } from '@playwright/test'
import { signInAsDemo } from './helpers/stack'

// INGEST-04: the Integrations page on the dev stack. A source (CBAS) names a berth nobody mapped: the gateway quarantines
// the record, an admin adds the mapping on the page and replays the record, and the event is published to the canonical
// topic. The original quarantine row stays as it was and is linked to its replay. The source posts to the gateway
// (E2E_GATEWAY_URL, with the demo key of the stack), people use the page, which talks to castor-core only (rule R7).
const gatewayUrl = process.env.E2E_GATEWAY_URL
const demoKey = process.env.GATEWAY_DEMO_KEY

test.skip(
  !process.env.E2E_BASE_URL || !gatewayUrl || !demoKey,
  'needs the dev stack: E2E_BASE_URL, E2E_GATEWAY_URL and GATEWAY_DEMO_KEY (the root .env)',
)

const basic = (user: string, password: string) =>
  `Basic ${Buffer.from(`${user}:${password}`).toString('base64')}`

async function openIntegrations(page: Page) {
  await signInAsDemo(page, 'admin')
  // The session lives in memory only: go there through the app bar, not by address
  await page.getByRole('link', { name: 'Integrations' }).click()
  await expect(page.getByRole('tab', { name: 'Sources' })).toHaveAttribute('aria-selected', 'true')
}

test('an unknown mapping is quarantined, the admin maps it and replays it, and the event is published', async ({
  page,
  request,
}) => {
  const run = Date.now().toString(36)
  const berth = `B-E2E-${run}`
  const msgId = `CBAS-E2E-${run}`
  const canonicalId = `e2e-berth-${run}`

  // 1. CBAS sends a call that names a berth no mapping knows: accepted at the door, refused by the adapter
  const sent = await request.post(`${gatewayUrl}/api/v1/ports/westports/integrations/cbas/events`, {
    headers: { 'X-Castor-Key': demoKey ?? '' },
    data: {
      msgId,
      seq: 1,
      callRef: `MV-E2E-${run}`,
      vessel: { name: 'MV E2E', imo: '9321483', loa: 180.5, beam: 30.2 },
      berthRef: berth,
      status: 'EXPECTED',
      eta: '2026-10-29T06:00:00Z',
    },
  })
  expect(sent.status()).toBe(202)
  const { receptionId } = (await sent.json()) as { receptionId: string }

  // 2. Sources: CBAS has just sent something and some of it was quarantined
  await openIntegrations(page)
  const cbas = page.getByRole('row', { name: /cbas/ })
  await expect(cbas.getByText('Fresh')).toBeVisible({ timeout: 20_000 })
  await cbas.getByRole('button', { name: /quarantined from cbas/ }).click()

  // 3. Quarantine: the record is there, refused for its berth, with the payload as it was sent
  await expect(page.getByRole('tab', { name: 'Quarantine' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await page.getByLabel('Find').fill(msgId)
  await page.getByRole('button', { name: new RegExp(`Open record ${msgId}`) }).click()
  const drawer = page.getByRole('dialog', { name: 'Quarantined record' })
  await expect(drawer.getByText('UNKNOWN_MAPPING').first()).toBeVisible()
  await expect(drawer.getByLabel('Payload (read-only)')).toContainText(`"berthRef": "${berth}"`)
  await expect(drawer.getByText('Not replayed.')).toBeVisible()

  // 4. An admin adds the mapping from the record
  await drawer.getByRole('button', { name: `Add mapping for ${berth}` }).click()
  const form = page.getByRole('dialog', { name: 'New identity mapping' })
  await expect(form.getByLabel('Source id')).toHaveValue(berth)
  await form.getByLabel('Canonical id').fill(canonicalId)
  await form.getByRole('button', { name: 'Add mapping' }).click()
  await expect(page.getByText(`Mapping added for ${berth}`)).toBeVisible()
  const mappings = page.getByRole('table', { name: 'Identity mappings' })
  await expect(mappings.getByRole('row', { name: new RegExp(berth) })).toContainText(canonicalId)

  // 5. Replay the original: a new record goes through the pipeline and its event reaches the canonical topic
  await page.getByRole('tab', { name: 'Quarantine' }).click()
  await page.getByLabel('Find').fill(msgId)
  await page.getByRole('button', { name: new RegExp(`Open record ${msgId}`) }).click()
  await drawer.getByRole('button', { name: 'Replay', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Replay', exact: true }).click()
  await expect(drawer.getByText('Accepted')).toBeVisible({ timeout: 30_000 })
  await expect(drawer.getByText('1 of 1 published')).toBeVisible({ timeout: 30_000 })

  // 6. The original row is exactly what it was, and linked to the replay; nothing was deleted
  const detail = await request.get(
    `/api/v1/ports/westports/integrations/quarantine/${receptionId}`,
    {
      headers: {
        Authorization: basic('admin', 'admin'),
      },
    },
  )
  expect(detail.status()).toBe(200)
  const body = await detail.json()
  expect(body.entry).toMatchObject({
    receptionId,
    sourceEventId: msgId,
    reasonCode: 'UNKNOWN_MAPPING',
    subjectId: berth,
    replayOf: null,
    replays: 1,
  })
  expect(body.raw.berthRef).toBe(berth)
  expect(body.attempts).toHaveLength(1)
  expect(body.attempts[0]).toMatchObject({ status: 'NORMALIZED', events: 1, published: 1 })
  expect(body.attempts[0].receptionId).not.toBe(receptionId)
  await drawer.getByRole('button', { name: 'Close' }).click()
  await expect(
    page.getByRole('row', { name: new RegExp(msgId) }).getByText('Replayed, accepted'),
  ).toBeVisible()
})

test('a viewer is refused the integrations by the API and has no link to them', async ({
  page,
  request,
}) => {
  const refused = await request.get('/api/v1/ports/westports/integrations/sources', {
    headers: { Authorization: basic('viewer', 'viewer') },
  })
  expect(refused.status()).toBe(403)
  const replay = await request.post(
    '/api/v1/ports/westports/integrations/quarantine/0198a6f0-0000-7000-8000-000000000001/replay',
    { headers: { Authorization: basic('viewer', 'viewer') } },
  )
  expect(replay.status()).toBe(403)

  await signInAsDemo(page, 'viewer')
  await expect(page.getByRole('link', { name: 'Integrations' })).toHaveCount(0)
})

test('the gateway admin API is not reachable through the web port (rule R7)', async ({
  request,
}) => {
  const response = await request.get('/internal/v1/ports/westports/sources', {
    headers: {
      Authorization: `Bearer ${process.env.CASTOR_SERVICE_TOKEN ?? 'local-service-token'}`,
    },
  })

  // nginx knows only /api (to castor-core) and the app: this path is the app's fallback page, never the gateway's JSON
  expect(response.headers()['content-type']).toContain('text/html')
  expect(await response.text()).not.toContain('freshnessSlaSeconds')
})
