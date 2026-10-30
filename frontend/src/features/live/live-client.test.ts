import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  LiveClient,
  OFFLINE_AFTER_MS,
  backoffDelay,
  type LiveDeps,
  type SocketLike,
} from './live-client'
import { useLiveStore } from './live-store'
import { serverNow } from './live-store'
import type { StateSnapshot } from './protocol'

class FakeSocket implements SocketLike {
  onopen: SocketLike['onopen'] = null
  onmessage: SocketLike['onmessage'] = null
  onclose: SocketLike['onclose'] = null
  onerror: SocketLike['onerror'] = null
  closed = false
  readonly ticket: string
  constructor(ticket: string) {
    this.ticket = ticket
  }
  close() {
    this.closed = true
  }
  open() {
    this.onopen?.({})
  }
  send(message: object) {
    this.onmessage?.({ data: JSON.stringify(message) })
  }
  drop(code = 1006) {
    this.onclose?.({ code })
  }
}

const eq = (id: string, version: number, status = 'WORKING') => ({
  id,
  assetId: id,
  status,
  version,
  quality: 'GOOD',
  source: 'sensors',
  observedAt: '2026-10-30T08:00:00Z',
  freshnessSlaS: 120,
})

const snapshotOf = (sequence: number, equipment = [eq('crane-1', 1)]): StateSnapshot => ({
  sequence,
  serverTime: '2026-10-30T08:00:00Z',
  clockSpeed: 1,
  equipment,
  vesselCalls: [],
  inventories: [],
  openGateVisits: 0,
  openReconciliationItems: 0,
})

const delta = (sequence: number, id: string, version: number, status = 'IDLE') => ({
  type: 'delta' as const,
  sequence,
  entityType: 'equipment',
  entityId: id,
  entityVersion: version,
  occurredAt: '2026-10-30T08:00:01Z',
  data: eq(id, version, status),
})

let sockets: FakeSocket[]
let tickets: number
let snapshots: StateSnapshot[]
let ticketFails: boolean
let snapshotCalls: number
let deps: LiveDeps
let client: LiveClient

const status = () => useLiveStore.getState().status
const crane = () => useLiveStore.getState().equipment['crane-1']

/** Lets ticket and snapshot promises settle without moving the timers. */
const flush = () => vi.advanceTimersByTimeAsync(0)

async function connectLive(snapshot = snapshotOf(10)) {
  snapshots = [snapshot]
  client.start()
  await flush()
  sockets.at(-1)!.open()
  await flush()
  return sockets.at(-1)!
}

beforeEach(() => {
  vi.useFakeTimers()
  useLiveStore.getState().reset()
  sockets = []
  tickets = 0
  snapshotCalls = 0
  snapshots = [snapshotOf(10)]
  ticketFails = false
  deps = {
    fetchTicket: async () => {
      if (ticketFails) throw new Error('refused')
      return { ticket: `t${++tickets}`, expiresAt: '2026-10-30T08:00:30Z' }
    },
    fetchSnapshot: async () => {
      snapshotCalls++
      return snapshots[Math.min(snapshotCalls - 1, snapshots.length - 1)]!
    },
    openSocket: (ticket) => {
      const socket = new FakeSocket(ticket)
      sockets.push(socket)
      return socket
    },
    perfNow: () => performance.now(),
    wallNow: () => Date.now(),
  }
  client = new LiveClient(deps)
})

afterEach(() => {
  client.stop()
  vi.useRealTimers()
})

describe('live client', () => {
  it('gets a ticket, opens the stream with it, fetches the snapshot and goes live', async () => {
    const socket = await connectLive()

    expect(socket.ticket).toBe('t1')
    expect(status()).toBe('live')
    expect(crane()?.version).toBe(1)
    expect(useLiveStore.getState().sequence).toBe(10)
  })

  it('buffers deltas until the snapshot arrived, drops those it contains and applies the rest in order', async () => {
    snapshots = [snapshotOf(10)]
    client.start()
    await flush()
    const socket = sockets[0]!
    socket.open()
    socket.send(delta(12, 'crane-1', 3, 'DOWN'))
    socket.send(delta(10, 'crane-1', 1, 'OLD'))
    socket.send(delta(11, 'crane-1', 2, 'IDLE'))
    await flush()

    expect(status()).toBe('live')
    expect(crane()).toMatchObject({ version: 3, status: 'DOWN' })
    expect(useLiveStore.getState().sequence).toBe(12)
  })

  it('applies live deltas in order and counts only consecutive ones', async () => {
    const socket = await connectLive()
    socket.send(delta(11, 'crane-2', 1))
    socket.send(delta(12, 'crane-1', 2, 'DOWN'))

    expect(useLiveStore.getState().sequence).toBe(12)
    expect(useLiveStore.getState().equipment['crane-2']).toBeDefined()
    expect(crane()?.status).toBe('DOWN')
  })

  it('ignores a duplicate delta', async () => {
    const socket = await connectLive()
    socket.send(delta(11, 'crane-1', 2, 'DOWN'))
    socket.send(delta(11, 'crane-1', 2, 'DOWN'))
    socket.send(delta(10, 'crane-1', 5, 'ELSE'))

    expect(status()).toBe('live')
    expect(sockets).toHaveLength(1)
    expect(crane()).toMatchObject({ version: 2, status: 'DOWN' })
    expect(useLiveStore.getState().sequence).toBe(11)
  })

  it('ignores a delta whose entityVersion is not above the stored one', async () => {
    const socket = await connectLive(snapshotOf(10, [eq('crane-1', 5)]))
    socket.send(delta(11, 'crane-1', 5, 'SAME'))
    socket.send(delta(12, 'crane-1', 4, 'OLDER'))

    expect(crane()).toMatchObject({ version: 5, status: 'WORKING' })
    // the sequence still moves on: the delta was seen, only its entity was not newer
    expect(useLiveStore.getState().sequence).toBe(12)
    expect(status()).toBe('live')
  })

  it('treats a gap as a reason to start again with a fresh snapshot', async () => {
    const socket = await connectLive()
    snapshots = [snapshotOf(10), snapshotOf(20, [eq('crane-1', 9)])]
    socket.send(delta(13, 'crane-1', 2))

    expect(socket.closed).toBe(true)
    expect(status()).toBe('reconnecting')
    expect(useLiveStore.getState().attempt).toBe(1)

    await vi.advanceTimersByTimeAsync(1_000)
    expect(sockets).toHaveLength(2)
    sockets[1]!.open()
    await flush()

    expect(snapshotCalls).toBe(2)
    expect(status()).toBe('live')
    expect(crane()?.version).toBe(9)
    expect(useLiveStore.getState().sequence).toBe(20)
  })

  it('treats a heartbeat that is ahead of the last applied delta as a gap, and corrects the clock otherwise', async () => {
    const socket = await connectLive()
    socket.send({
      type: 'heartbeat',
      sequence: 10,
      serverTime: '2026-10-30T08:01:00Z',
      clockSpeed: 2,
    })
    expect(status()).toBe('live')
    expect(useLiveStore.getState().clock).toMatchObject({
      serverTimeMs: Date.parse('2026-10-30T08:01:00Z'),
      clockSpeed: 2,
    })

    socket.send({
      type: 'heartbeat',
      sequence: 11,
      serverTime: '2026-10-30T08:01:15Z',
      clockSpeed: 1,
    })
    expect(socket.closed).toBe(true)
    expect(status()).toBe('reconnecting')
  })

  it('reconnects with a new ticket after a close, backing off 1 s, 2 s, 4 s ... up to 30 s', async () => {
    const socket = await connectLive()
    ticketFails = true
    socket.drop()
    expect(status()).toBe('reconnecting')

    for (const expected of [1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000]) {
      await vi.advanceTimersByTimeAsync(expected - 1)
      const attemptsBefore = useLiveStore.getState().attempt
      await vi.advanceTimersByTimeAsync(1)
      // the retry fired exactly at the delay: the failed ticket counted one more attempt
      expect(useLiveStore.getState().attempt).toBe(attemptsBefore + 1)
      expect(sockets).toHaveLength(1)
    }

    ticketFails = false
    await vi.advanceTimersByTimeAsync(30_000)
    expect(sockets).toHaveLength(2)
    sockets[1]!.open()
    await flush()
    expect(status()).toBe('live')
    expect(sockets[1]!.ticket).toBe('t2')
    expect(snapshotCalls).toBe(2)
  })

  it('resets the backoff once a snapshot is applied', async () => {
    const socket = await connectLive()
    socket.drop()
    await vi.advanceTimersByTimeAsync(1_000)
    sockets[1]!.drop()
    expect(useLiveStore.getState().attempt).toBe(2)
    await vi.advanceTimersByTimeAsync(2_000)
    sockets[2]!.open()
    await flush()
    expect(status()).toBe('live')

    sockets[2]!.drop(4001)
    expect(useLiveStore.getState().attempt).toBe(1)
    await vi.advanceTimersByTimeAsync(999)
    expect(sockets).toHaveLength(3)
    await vi.advanceTimersByTimeAsync(1)
    expect(sockets).toHaveLength(4)
  })

  it('goes Offline after 60 s without a connection and is Live again after the next snapshot', async () => {
    const socket = await connectLive()
    ticketFails = true
    socket.drop()
    await vi.advanceTimersByTimeAsync(OFFLINE_AFTER_MS - 1)
    expect(status()).toBe('reconnecting')
    await vi.advanceTimersByTimeAsync(1)
    expect(status()).toBe('offline')

    ticketFails = false
    await vi.advanceTimersByTimeAsync(30_000)
    sockets.at(-1)!.open()
    await flush()
    expect(status()).toBe('live')
  })

  it('reconnects when the snapshot cannot be fetched', async () => {
    snapshots = []
    deps.fetchSnapshot = async () => {
      snapshotCalls++
      if (snapshotCalls === 1) throw new Error('500')
      return snapshotOf(10)
    }
    client.start()
    await flush()
    sockets[0]!.open()
    await flush()
    expect(status()).toBe('reconnecting')
    expect(sockets[0]!.closed).toBe(true)

    await vi.advanceTimersByTimeAsync(1_000)
    sockets[1]!.open()
    await flush()
    expect(status()).toBe('live')
  })

  it('ignores malformed messages and deltas of an unknown entity type', async () => {
    const socket = await connectLive()
    socket.onmessage?.({ data: 'not json' })
    socket.onmessage?.({ data: JSON.stringify({ type: 'other' }) })
    socket.send({ ...delta(11, 'x', 1), entityType: 'hologram' })

    expect(status()).toBe('live')
    expect(useLiveStore.getState().sequence).toBe(11)
    expect(Object.keys(useLiveStore.getState().equipment)).toEqual(['crane-1'])
  })

  it('stops for good: closes the socket, empties the state and does not reconnect', async () => {
    const socket = await connectLive()
    client.stop()

    expect(socket.closed).toBe(true)
    expect(status()).toBe('idle')
    expect(crane()).toBeUndefined()
    await vi.advanceTimersByTimeAsync(120_000)
    expect(sockets).toHaveLength(1)
  })

  it('keeps one stream when started twice', async () => {
    client.start()
    client.start()
    await flush()
    expect(sockets).toHaveLength(1)
  })

  it('drops a ticket that arrives after stop', async () => {
    client.start()
    client.stop()
    await flush()
    expect(sockets).toHaveLength(0)
  })
})

describe('server clock', () => {
  it('is serverTime + elapsed x clockSpeed, set by the snapshot and corrected by a heartbeat', async () => {
    const socket = await connectLive({ ...snapshotOf(10), clockSpeed: 60 })
    const base = Date.parse('2026-10-30T08:00:00Z')
    const t0 = performance.now()
    expect(serverNow(t0)).toBeCloseTo(base, -1)
    expect(serverNow(t0 + 1_000)).toBeCloseTo(base + 60_000, -1)

    socket.send({
      type: 'heartbeat',
      sequence: 10,
      serverTime: '2026-10-30T09:00:00Z',
      clockSpeed: 1,
    })
    const t1 = performance.now()
    expect(serverNow(t1 + 5_000)).toBeCloseTo(Date.parse('2026-10-30T09:00:05Z'), -1)
  })
})

describe('backoffDelay', () => {
  it('doubles from 1 s and stops at 30 s', () => {
    expect([0, 1, 2, 3, 4, 5, 6, 20].map(backoffDelay)).toEqual([
      1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000,
    ])
  })
})
