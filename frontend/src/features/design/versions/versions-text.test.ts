import { describe, expect, it } from 'vitest'
import {
  assetsText,
  diffSummary,
  draftStatusText,
  formatChangeTime,
  publishBlockedMessage,
  publishedMessage,
  restorePrompt,
  restoredMessage,
} from './versions-text'

describe('versions text', () => {
  it('writes the messages of the Versions tab', () => {
    expect(publishBlockedMessage(3)).toBe('Publishing blocked: 3 error(s). Fix them first.')
    expect(publishedMessage(4)).toBe('Version 4 published. Monitor shows it now.')
    expect(restorePrompt(2)).toBe('Load version 2 into a new draft? Asset IDs stay the same.')
    expect(restoredMessage(2)).toBe(
      'Version 2 loaded. Asset IDs are unchanged. Publish to make it live.',
    )
  })

  it('counts added, changed and removed assets', () => {
    expect(
      diffSummary({
        added: [{ assetId: 'a', code: 'A', name: 'A', type: 'T' }],
        removed: [],
        changed: [
          { assetId: 'b', code: 'B', name: 'B', fields: ['geometry'] },
          { assetId: 'c', code: 'C', name: 'C', fields: ['name'] },
        ],
      }),
    ).toBe('1 added, 2 changed, 0 removed')
  })

  it('writes "dd Mon, HH:MM" in the time zone of the port', () => {
    expect(formatChangeTime('2026-10-28T02:35:00Z', 'Asia/Kuala_Lumpur')).toBe('28 Oct, 10:35')
    expect(formatChangeTime('nonsense')).toBe('nonsense')
  })

  it('names the draft by its base and counts assets', () => {
    expect(draftStatusText(null)).toBe('Blank draft')
    expect(draftStatusText(3)).toBe('Draft (based on v3)')
    expect(assetsText(1)).toBe('1 asset')
    expect(assetsText(301)).toBe('301 assets')
  })
})
