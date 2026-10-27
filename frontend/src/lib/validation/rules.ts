/**
 * The rule catalogue of README section 6: code, level and message wording. The same list is kept in
 * fixtures/validation-rules.json and in the backend (ValidationMessages); tests on both sides compare their copy with
 * the file, so the browser and the server never word a rule differently. Messages with `{slots}` are filled by
 * {@link formatMessage}.
 */

export type IssueLevel = 'ERROR' | 'WARNING'

export interface Rule {
  code: string
  /** Names the wording when a code has more than one; 'default' otherwise. */
  variant: string
  level: IssueLevel
  template: string
}

const rule = (code: string, variant: string, level: IssueLevel, template: string): Rule => ({
  code,
  variant,
  level,
  template,
})

export const RULES: readonly Rule[] = [
  rule('GEOM_INVALID', 'default', 'ERROR', '{name} has an invalid shape'),
  // named: the Issues list, where the row has room for the name; edges: the drawing tool, which needs the edges
  rule(
    'SELF_INTERSECTION',
    'named',
    'ERROR',
    '{name} has a self-intersecting outline and cannot be published',
  ),
  rule('SELF_INTERSECTION', 'edges', 'ERROR', 'The outline crosses itself (edges {i} and {j})'),
  rule('TOO_FEW_POINTS', 'polygon', 'ERROR', '{name} needs at least three corners'),
  rule('TOO_FEW_POINTS', 'line', 'ERROR', '{name} needs at least two points'),
  rule('TOO_SMALL', 'default', 'ERROR', 'The outline is smaller than 4 m2'),
  rule('WRONG_GEOMETRY_KIND', 'default', 'ERROR', '{name}: geometry does not match type {type}'),
  rule('OUT_OF_PORT', 'default', 'ERROR', '{name} lies outside the port extent'),
  rule('ON_WATER', 'default', 'ERROR', 'Extends past the quay edge into the water'),
  rule('OUTSIDE_LAND', 'default', 'ERROR', 'Outside the land area'),
  rule(
    'IN_EXCLUSION_ZONE',
    'default',
    'ERROR',
    'Inside safety exclusion zone {zone}. Move it, or record a documented override in the inspector',
  ),
  rule('OVERLAP', 'default', 'ERROR', 'Overlaps {other}'),
  rule('BERTH_OVERLAP', 'default', 'ERROR', 'Overlaps {other} along the quay'),
  rule('CRANE_OFF_BERTH', 'default', 'ERROR', 'A rail-mounted quay crane must stand on a berth'),
  rule('DIM_OUT_OF_RANGE', 'min', 'ERROR', '{label}: enter a number of at least {limit}'),
  rule('DIM_OUT_OF_RANGE', 'max', 'ERROR', '{label}: enter a number of at most {limit}'),
  rule('UNKNOWN_TYPE', 'default', 'ERROR', '{name}: unknown asset type {type}'),
  rule(
    'TRACE_SOURCE_NOT_ALLOWED',
    'default',
    'ERROR',
    '{name} was traced from unlicensed imagery. Publishing is blocked until its source is replaced with licensed or surveyed data',
  ),
  rule('ON_ROAD', 'default', 'WARNING', '{block} sits on road {road}'),
  rule(
    'PILE_SLOT_TOO_SMALL',
    'default',
    'WARNING',
    '{block}: a {bp} x {rp} m slot is too small to hold a pile',
  ),
  rule('APPROXIMATE_SOURCE', 'one', 'WARNING', '1 shape has no survey'),
  rule('APPROXIMATE_SOURCE', 'many', 'WARNING', '{n} shapes have no survey'),
]

/** Fills the slots of one wording of a rule; throws for a code or variant that is not in the catalogue. */
export function formatMessage(
  code: string,
  variant: string,
  slots: Readonly<Record<string, string | number>> = {},
): string {
  const found = RULES.find((r) => r.code === code && r.variant === variant)
  if (!found) throw new Error(`Unknown rule ${code}/${variant}`)
  return Object.entries(slots).reduce(
    (text, [key, value]) => text.replaceAll(`{${key}}`, String(value)),
    found.template,
  )
}

/** The level of a code of the catalogue, or null for a code this version of the app does not know. */
export function ruleLevel(code: string): IssueLevel | null {
  return RULES.find((r) => r.code === code)?.level ?? null
}
