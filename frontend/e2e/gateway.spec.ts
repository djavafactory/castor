import { expect, test } from '@playwright/test'

// INGEST-02: the ingest gateway on the dev stack. It answers on the host loopback only (E2E_GATEWAY_URL) with the demo
// key of the stack (GATEWAY_DEMO_KEY, from .env). No screen is involved: this is the API the e2e helper postCanonical()
// and the emulator use, so the spec checks what a source sees. What happens after the 202 (normalizer, outbox, Kafka)
// is proven by the gateway's integration tests.
const gatewayUrl = process.env.E2E_GATEWAY_URL
const demoKey = process.env.GATEWAY_DEMO_KEY
const eventsPath = '/api/v1/ports/westports/integrations/canonical/events'

const canonicalEvent = (sourceEventId: string) => ({
  schemaVersion: 1,
  sourceSystem: 'E2E',
  sourceEventId,
  type: 'equipment.status.changed',
  entityType: 'EQUIPMENT',
  entityId: 'crane-e2e',
  aggregateId: 'crane-e2e',
  occurredAt: new Date().toISOString(),
  quality: 'SIMULATED',
  payload: { status: 'IDLE' },
})

test.describe('Ingest gateway', () => {
  test.skip(
    !gatewayUrl || !demoKey,
    'needs E2E_GATEWAY_URL and GATEWAY_DEMO_KEY (the dev stack and the root .env)',
  )

  const headers = { 'X-Castor-Key': demoKey ?? '' }

  test('answers 202 once and then recognises a repeat of the same source event', async ({
    request,
  }) => {
    const sourceEventId = `e2e-${Date.now()}-${Math.random().toString(16).slice(2)}`

    const first = await request.post(`${gatewayUrl}${eventsPath}`, {
      headers,
      data: canonicalEvent(sourceEventId),
    })
    expect(first.status()).toBe(202)
    const { receptionId } = await first.json()
    expect(receptionId).toMatch(/^[0-9a-f-]{36}$/)

    const again = await request.post(`${gatewayUrl}${eventsPath}`, {
      headers,
      data: canonicalEvent(sourceEventId),
    })
    expect(again.status()).toBe(200)
    expect(await again.json()).toEqual({ receptionId, duplicate: true })
  })

  test('answers an array per item', async ({ request }) => {
    const id = `e2e-batch-${Date.now()}-${Math.random().toString(16).slice(2)}`

    const response = await request.post(`${gatewayUrl}${eventsPath}`, {
      headers,
      data: [canonicalEvent(id), { type: 'no id or time' }],
    })

    expect(response.status()).toBe(207)
    const { items } = await response.json()
    expect(items.map((item: { status: number }) => item.status)).toEqual([202, 422])
  })

  test('refuses what it must, each with Problem Details', async ({ request }) => {
    const wrongKey = await request.post(`${gatewayUrl}${eventsPath}`, {
      headers: { 'X-Castor-Key': 'not-the-key' },
      data: canonicalEvent('e2e-wrong-key'),
    })
    expect(wrongKey.status()).toBe(401)
    expect(wrongKey.headers()['content-type']).toContain('application/problem+json')

    const disabled = await request.post(
      `${gatewayUrl}/api/v1/ports/westports/integrations/cbas/events`,
      {
        headers,
        data: canonicalEvent('e2e-disabled'),
      },
    )
    expect(disabled.status()).toBe(403)

    const malformed = await request.post(`${gatewayUrl}${eventsPath}`, {
      headers: { ...headers, 'Content-Type': 'application/json' },
      data: '{"sourceEventId":',
    })
    expect(malformed.status()).toBe(400)

    const incomplete = await request.post(`${gatewayUrl}${eventsPath}`, {
      headers,
      data: { type: 'x' },
    })
    expect(incomplete.status()).toBe(422)
    expect(
      (await incomplete.json()).errors.map((error: { pointer: string }) => error.pointer),
    ).toEqual(['/sourceEventId', '/occurredAt'])

    const oversize = await request.post(`${gatewayUrl}${eventsPath}`, {
      headers,
      data: { ...canonicalEvent('e2e-oversize'), padding: 'x'.repeat(300 * 1024) },
    })
    expect(oversize.status()).toBe(413)
  })

  test('is not reachable through the web port (rule R7)', async ({ request, baseURL }) => {
    const response = await request.post(`${baseURL}${eventsPath}`, {
      headers,
      data: canonicalEvent('e2e-via-nginx'),
    })

    // nginx sends /api to castor-core, which knows no such route and wants a signed-in user
    expect([401, 404]).toContain(response.status())
  })
})
