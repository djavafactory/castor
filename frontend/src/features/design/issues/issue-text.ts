import type { ValidationIssue } from '@/lib/api'

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

/** "2 errors (block publishing), 1 warning"; a single error blocks publishing too. */
export function issueSummary(errors: number, warnings: number): string {
  const blocks = errors === 1 ? 'blocks' : 'block'
  return `${plural(errors, 'error', 'errors')} (${blocks} publishing), ${plural(warnings, 'warning', 'warnings')}`
}

/** How many names a row lists before it says "and N more" (the no-survey warning names every shape). */
export const NAMES_SHOWN = 3

/** The assets of an issue by name, in the order the server gives them; unknown ids are left out. */
export function issueNames(
  issue: ValidationIssue,
  nameOf: (id: string) => string | undefined,
): string {
  const names = issue.assetIds.map(nameOf).filter((n): n is string => n !== undefined)
  const shown = names.slice(0, NAMES_SHOWN).join(' · ')
  return names.length > NAMES_SHOWN ? `${shown} and ${names.length - NAMES_SHOWN} more` : shown
}
