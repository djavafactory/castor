import { readClock } from './clock'
import { useLiveStore } from './live-store'
import {
  type Heartbeat,
  type StateDelta,
  type StateSnapshot,
  type StreamTicket,
  parseServerMessage,
} from './protocol'

export const BACKOFF_START_MS = 1_000
export const BACKOFF_MAX_MS = 30_000
/** Without a connection for this long the chip says Offline (the client keeps trying). */
export const OFFLINE_AFTER_MS = 60_000

/** The part of `WebSocket` the client uses, so tests can inject a fake. */
export interface SocketLike {
  onopen: ((event: unknown) => void) | null
  onmessage: ((event: { data: unknown }) => void) | null
  onclose: ((event: { code: number }) => void) | null
  onerror: ((event: unknown) => void) | null
  close(code?: number): void
}

export interface LiveDeps {
  fetchTicket(signal: AbortSignal): Promise<StreamTicket>
  fetchSnapshot(signal: AbortSignal): Promise<StateSnapshot>
  openSocket(ticket: string): SocketLike
  /** `performance.now()`. */
  perfNow(): number
  /** `Date.now()`. */
  wallNow(): number
}

/** Delay before reconnect number `failures + 1`: 1 s, doubling, at most 30 s. */
export function backoffDelay(failures: number): number {
  return Math.min(BACKOFF_START_MS * 2 ** failures, BACKOFF_MAX_MS)
}

/**
 * The browser side of the live stream contract (README, STATE-02): ticket, WebSocket, buffered deltas, snapshot,
 * ordered deltas. A gap, a close or an error starts again with a fresh snapshot after a backoff. Plain TypeScript, no
 * React: the store is the only output.
 */
export class LiveClient {
  private running = false
  /** Bumped by every connection attempt and by stop(); callbacks of an older attempt do nothing. */
  private generation = 0
  private abort: AbortController | null = null
  private socket: SocketLike | null = null
  private retryTimer: ReturnType<typeof setTimeout> | null = null
  private offlineTimer: ReturnType<typeof setTimeout> | null = null
  /** Failed attempts since the last applied snapshot. */
  private failures = 0
  private live = false
  /** True once 60 s passed without a connection; cleared by the next snapshot. */
  private offline = false
  private buffer: StateDelta[] = []
  private lastApplied = 0

  private readonly deps: LiveDeps

  constructor(deps: LiveDeps) {
    this.deps = deps
  }

  get isRunning(): boolean {
    return this.running
  }

  start(): void {
    if (this.running) return
    this.running = true
    this.failures = 0
    this.offline = false
    useLiveStore.getState().setConnection('connecting', 0)
    this.armOffline()
    void this.connect()
  }

  stop(): void {
    this.running = false
    this.generation++
    this.teardown()
    this.clearTimer('retryTimer')
    this.clearTimer('offlineTimer')
    this.live = false
    useLiveStore.getState().reset()
  }

  private clearTimer(name: 'retryTimer' | 'offlineTimer'): void {
    if (this[name] !== null) clearTimeout(this[name])
    this[name] = null
  }

  private teardown(): void {
    this.abort?.abort()
    this.abort = null
    const socket = this.socket
    this.socket = null
    if (socket) {
      socket.onopen = socket.onmessage = socket.onclose = socket.onerror = null
      try {
        socket.close()
      } catch {
        // already closed
      }
    }
  }

  private async connect(): Promise<void> {
    const generation = ++this.generation
    this.teardown()
    this.live = false
    this.buffer = []
    const abort = new AbortController()
    this.abort = abort
    const current = () => this.running && generation === this.generation

    let ticket: StreamTicket
    try {
      ticket = await this.deps.fetchTicket(abort.signal)
    } catch {
      if (current()) this.retryLater()
      return
    }
    if (!current()) return

    let socket: SocketLike
    try {
      socket = this.deps.openSocket(ticket.ticket)
    } catch {
      this.retryLater()
      return
    }
    this.socket = socket
    socket.onmessage = (event) => {
      if (current()) this.onMessage(event.data)
    }
    socket.onclose = () => {
      if (current()) this.retryLater()
    }
    socket.onerror = () => {
      if (current()) this.retryLater()
    }
    // Deltas are buffered from the moment the socket opens; the snapshot is fetched after that
    socket.onopen = () => {
      if (current()) void this.loadSnapshot(generation, abort.signal)
    }
  }

  private async loadSnapshot(generation: number, signal: AbortSignal): Promise<void> {
    let snapshot: StateSnapshot
    try {
      snapshot = await this.deps.fetchSnapshot(signal)
    } catch {
      if (this.running && generation === this.generation) this.retryLater()
      return
    }
    if (!this.running || generation !== this.generation) return

    const store = useLiveStore.getState()
    const received = this.deps.perfNow()
    store.applySnapshot(snapshot, readClock(snapshot.serverTime, snapshot.clockSpeed, received))
    this.lastApplied = snapshot.sequence
    this.live = true
    this.failures = 0
    this.offline = false
    this.clearTimer('offlineTimer')
    store.setConnection('live', 0)
    store.touch(this.deps.wallNow())

    // Deltas at or below the snapshot are in it already; the rest follow in order
    const pending = this.buffer.filter((d) => d.sequence > snapshot.sequence)
    this.buffer = []
    pending.sort((a, b) => a.sequence - b.sequence)
    for (const delta of pending) {
      if (!this.live) return
      this.applyLive(delta)
    }
  }

  private onMessage(raw: unknown): void {
    const message = parseServerMessage(raw)
    if (!message) return
    useLiveStore.getState().touch(this.deps.wallNow())
    if (message.type === 'delta') {
      if (this.live) this.applyLive(message)
      else this.buffer.push(message)
    } else if (this.live) {
      this.onHeartbeat(message)
    }
  }

  private applyLive(delta: StateDelta): void {
    // A repeat of something already applied changes nothing
    if (delta.sequence <= this.lastApplied) return
    if (delta.sequence !== this.lastApplied + 1) {
      this.retryLater()
      return
    }
    this.lastApplied = delta.sequence
    const store = useLiveStore.getState()
    store.applyDelta(delta)
    store.setSequence(delta.sequence)
  }

  private onHeartbeat(heartbeat: Heartbeat): void {
    // The server has sent deltas this client has not seen
    if (heartbeat.sequence > this.lastApplied) {
      this.retryLater()
      return
    }
    const clock = readClock(heartbeat.serverTime, heartbeat.clockSpeed, this.deps.perfNow())
    if (clock) useLiveStore.getState().setClock(clock)
  }

  /** Closes everything of this attempt and reconnects after the backoff (the first retry after a loss waits 1 s). */
  private retryLater(): void {
    if (!this.running) return
    this.generation++
    this.teardown()
    this.live = false
    this.buffer = []
    if (this.retryTimer !== null) return
    const delay = backoffDelay(this.failures)
    this.failures++
    useLiveStore.getState().setConnection(this.offline ? 'offline' : 'reconnecting', this.failures)
    this.armOffline()
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null
      if (this.running) void this.connect()
    }, delay)
  }

  /** Starts the 60 s countdown to Offline unless it is running or the client is already offline. */
  private armOffline(): void {
    if (this.offlineTimer !== null || this.offline) return
    this.offlineTimer = setTimeout(() => {
      this.offlineTimer = null
      if (!this.running || this.live) return
      this.offline = true
      useLiveStore.getState().setConnection('offline', this.failures)
    }, OFFLINE_AFTER_MS)
  }
}
