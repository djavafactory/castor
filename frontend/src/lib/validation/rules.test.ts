import fixtureJson from '../../../../fixtures/validation-rules.json'
import { RULES, formatMessage, ruleLevel } from './rules'

interface Fixture {
  rules: { code: string; variant: string; level: string; template: string }[]
  examples: {
    code: string
    variant: string
    params: Record<string, string>
    message: string
  }[]
}
const fixture = fixtureJson as unknown as Fixture

describe('rule catalogue (shared with the backend through fixtures/validation-rules.json)', () => {
  it('has the codes, levels and wording of the fixture, in the same order', () => {
    expect(RULES.map((r) => ({ ...r }))).toEqual(fixture.rules)
  })

  it('words every example of the fixture the same', () => {
    expect(fixture.examples.length).toBeGreaterThanOrEqual(18)
    for (const example of fixture.examples) {
      expect(formatMessage(example.code, example.variant, example.params)).toBe(example.message)
    }
  })

  it('covers the 18 codes of the README', () => {
    expect(new Set(RULES.map((r) => r.code)).size).toBe(18)
  })

  it('knows the level of a code', () => {
    expect(ruleLevel('OVERLAP')).toBe('ERROR')
    expect(ruleLevel('APPROXIMATE_SOURCE')).toBe('WARNING')
    expect(ruleLevel('NOT_A_RULE')).toBeNull()
  })

  it('refuses a wording that does not exist', () => {
    expect(() => formatMessage('OVERLAP', 'nope')).toThrow('Unknown rule OVERLAP/nope')
  })

  it('names the crossing edges for the drawing tool', () => {
    expect(formatMessage('SELF_INTERSECTION', 'edges', { i: 1, j: 3 })).toBe(
      'The outline crosses itself (edges 1 and 3)',
    )
  })
})
