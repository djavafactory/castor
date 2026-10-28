import AxeBuilder from '@axe-core/playwright'
import { expect, type Page } from '@playwright/test'

type Impact = 'minor' | 'moderate' | 'serious' | 'critical'

interface A11yOptions {
  /** Selectors to leave out (e.g. the canvas). */
  exclude?: string[]
  /** Only these impacts fail the test; all of them by default. JOIN-03 asks for no serious or critical issue. */
  impacts?: Impact[]
}

/** Fails the test when the current page has WCAG 2.2 A/AA violations. */
export async function expectNoA11yViolations(
  page: Page,
  { exclude = [], impacts }: A11yOptions = {},
) {
  let builder = new AxeBuilder({ page }).withTags([
    'wcag2a',
    'wcag2aa',
    'wcag21a',
    'wcag21aa',
    'wcag22aa',
  ])
  for (const selector of exclude) builder = builder.exclude(selector)
  const { violations } = await builder.analyze()
  const failing = impacts
    ? violations.filter((v) => impacts.includes(v.impact as Impact))
    : violations
  expect(
    failing.map(
      (v) => `${v.id} (${v.impact}): ${v.nodes.length} node(s), e.g. ${v.nodes[0]?.target}`,
    ),
    'accessibility violations',
  ).toEqual([])
}

/** The JOIN-03 bar: no serious or critical WCAG 2.2 A/AA issue. */
export const expectNoSeriousA11yViolations = (page: Page, exclude: string[] = []) =>
  expectNoA11yViolations(page, { exclude, impacts: ['serious', 'critical'] })
