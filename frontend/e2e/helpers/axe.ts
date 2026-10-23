import AxeBuilder from '@axe-core/playwright'
import { expect, type Page } from '@playwright/test'

/** Fails the test when the current page has WCAG 2.1 A/AA violations. Pass selectors to leave out (e.g. the canvas). */
export async function expectNoA11yViolations(page: Page, exclude: string[] = []) {
  let builder = new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
  for (const selector of exclude) builder = builder.exclude(selector)
  const { violations } = await builder.analyze()
  expect(
    violations.map((v) => `${v.id}: ${v.nodes.length} node(s)`),
    'accessibility violations',
  ).toEqual([])
}
