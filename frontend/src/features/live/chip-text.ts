import type { ConnectionStatus } from './live-store'

export function chipText(status: ConnectionStatus, attempt: number): string {
  switch (status) {
    case 'live':
      return 'Live'
    case 'connecting':
      return 'Connecting...'
    case 'reconnecting':
      return `Reconnecting... (attempt ${attempt})`
    case 'offline':
      return 'Offline'
    default:
      return ''
  }
}

export function lastMessageText(at: number | null): string {
  if (at === null) return 'No message received yet'
  return `Last message at ${new Date(at).toLocaleTimeString('en-GB')}`
}
