import { describe, expect, it } from 'vitest'
import { parseServerMessage } from './protocol'
import { streamUrl } from './live-stream'

const page = (href: string) => new URL(href) as unknown as Location

describe('streamUrl', () => {
  it('uses the page host with ws for a relative API root', () => {
    expect(streamUrl('/api', 'WESTPORTS', 'a b', page('http://localhost:8080/'))).toBe(
      'ws://localhost:8080/api/v1/ports/WESTPORTS/stream?ticket=a%20b',
    )
  })

  it('uses wss on https pages', () => {
    expect(streamUrl('/api/', 'westports', 't', page('https://castor.example/x'))).toBe(
      'wss://castor.example/api/v1/ports/westports/stream?ticket=t',
    )
  })

  it('keeps the host of an absolute API root', () => {
    expect(
      streamUrl('http://localhost:8081/api', 'westports', 't', page('http://localhost:5173/')),
    ).toBe('ws://localhost:8081/api/v1/ports/westports/stream?ticket=t')
  })
})

describe('parseServerMessage', () => {
  it('reads a delta and a heartbeat and refuses the rest', () => {
    expect(
      parseServerMessage(
        '{"type":"heartbeat","sequence":4,"serverTime":"2026-10-30T08:00:00Z","clockSpeed":1}',
      ),
    ).toMatchObject({ type: 'heartbeat', sequence: 4 })
    expect(parseServerMessage('{"type":"heartbeat","sequence":4}')).toBeNull()
    expect(parseServerMessage('nope')).toBeNull()
    expect(parseServerMessage(new ArrayBuffer(1))).toBeNull()
  })
})
