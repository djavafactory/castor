import { describe, expect, it } from 'vitest'
import { issueNames, issueSummary } from './issue-text'

describe('issueSummary', () => {
  it('words the counts the way the Issues tab shows them', () => {
    expect(issueSummary(3, 2)).toBe('3 errors (block publishing), 2 warnings')
    expect(issueSummary(0, 0)).toBe('0 errors (block publishing), 0 warnings')
  })

  it('uses the singular for one', () => {
    expect(issueSummary(1, 1)).toBe('1 error (blocks publishing), 1 warning')
  })
})

describe('issueNames', () => {
  const names: Record<string, string> = { a: 'Hall A', b: 'Hall B', c: 'Hall C', d: 'Hall D' }
  const of = (assetIds: string[]) => ({
    code: 'X',
    level: 'ERROR' as const,
    message: '',
    assetIds,
  })

  it('lists the assets in order, leaving out the ones the draft does not have', () => {
    expect(issueNames(of(['b', 'zzz', 'a']), (id) => names[id])).toBe('Hall B · Hall A')
  })

  it('stops after three and counts the rest', () => {
    expect(issueNames(of(['a', 'b', 'c', 'd']), (id) => names[id])).toBe(
      'Hall A · Hall B · Hall C and 1 more',
    )
  })

  it('is empty when no asset is known', () => {
    expect(issueNames(of([]), (id) => names[id])).toBe('')
  })
})
