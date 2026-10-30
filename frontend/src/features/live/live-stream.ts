import { apiGet, apiRequest } from '@/lib/api-client'
import { LiveClient, type LiveDeps, type SocketLike } from './live-client'
import { snapshotSchema, ticketSchema } from './protocol'

/**
 * The WebSocket address of a port's stream. A relative API root (`/api`, the container setup) means the page's own
 * host with ws or wss; an absolute one (local development, `http://localhost:8081/api`) keeps its host.
 */
export function streamUrl(
  apiRoot: string,
  portCode: string,
  ticket: string,
  page: Location,
): string {
  const absolute = /^https?:\/\//.test(apiRoot)
  const root = absolute ? new URL(apiRoot) : new URL(apiRoot, page.href)
  const protocol = root.protocol === 'https:' ? 'wss:' : 'ws:'
  const path = root.pathname.replace(/\/+$/, '')
  return `${protocol}//${root.host}${path}/v1/ports/${encodeURIComponent(portCode)}/stream?ticket=${encodeURIComponent(ticket)}`
}

/** The real dependencies: Basic-auth REST calls through the API client, a browser WebSocket and the real clocks. */
export function browserDeps(portCode: string, apiRoot: string): LiveDeps {
  const base = `/v1/ports/${encodeURIComponent(portCode)}`
  return {
    fetchTicket: async (signal) => {
      const { data } = await apiRequest<unknown>(`${base}/stream/ticket`, {
        method: 'POST',
        signal,
      })
      return ticketSchema.parse(data)
    },
    fetchSnapshot: async (signal) => {
      const { data } = await apiGet<unknown>(`${base}/state/snapshot`, { signal })
      return snapshotSchema.parse(data)
    },
    openSocket: (ticket) =>
      new WebSocket(streamUrl(apiRoot, portCode, ticket, window.location)) as SocketLike,
    perfNow: () => performance.now(),
    wallNow: () => Date.now(),
  }
}

let active: { portCode: string; client: LiveClient } | null = null

/** One stream per port per tab: asking again for the running port does nothing. */
export function startLiveStream(portCode: string, apiRoot: string): void {
  if (active?.portCode === portCode) return
  stopLiveStream()
  const client = new LiveClient(browserDeps(portCode, apiRoot))
  active = { portCode, client }
  client.start()
}

/** Closes the stream and empties the live state. */
export function stopLiveStream(): void {
  active?.client.stop()
  active = null
}
