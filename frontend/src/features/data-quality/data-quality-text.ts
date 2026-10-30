import type { DeadLetter, DeadLetterState, ReconciliationItem, ReconciliationKind } from '@/lib/api'
import { shortId } from '@/features/integrations/integrations-text'

export const KIND_LABEL: Record<ReconciliationKind, string> = {
  STALE_EVENT: 'Stale event',
  SOURCE_CONFLICT: 'Source conflict',
  NEGATIVE_BALANCE: 'Negative balance',
  CAPACITY_EXCEEDED: 'Over capacity',
}

/** What each kind means for the person looking at it, and what resolving it does and does not do. */
export const KIND_HELP: Record<ReconciliationKind, string> = {
  STALE_EVENT:
    'An event older than the state already held arrived. It changed nothing; the state was not moved back.',
  SOURCE_CONFLICT:
    'Two sources disagree. The written rule of the source of record decided; the other value is kept in the history.',
  NEGATIVE_BALANCE:
    'A movement would have taken a balance below zero. It is recorded, the balance was left as it was and the row waits for a correction.',
  CAPACITY_EXCEEDED:
    'A movement took the stock above the usable capacity of the area. It is kept as reported.',
}

export const STATE_LABEL: Record<DeadLetterState, string> = {
  OPEN: 'Not replayed',
  REPLAYED: 'Replayed, applied',
  REPLAY_FAILED: 'Replayed, failed again',
}

/** What each reason means; the dead letter holds the event as it was received. */
export const REASON_HELP: Record<string, string> = {
  INVALID_EVENT: 'The event is not valid, or a value in it cannot be applied.',
  UNKNOWN_TYPE: 'No handler exists for this type of event.',
  UNKNOWN_PORT: 'The event names a port Castor does not know.',
  UNIT_MISMATCH: 'The unit does not fit: units are never converted.',
  UNKNOWN_ENTITY: 'An asset, berth, call, lot or cargo family the event names is not known.',
  OUT_OF_EXTENT: 'A position outside the port extent.',
  PROCESSING_FAILED: 'The projector kept failing on this event.',
}

/** "45 s", "12 min", "3 h 5 min", "2 d": how long ago, never negative. */
export function ageText(createdAt: string | null | undefined, nowMs: number): string {
  if (!createdAt) return 'Unknown'
  const then = Date.parse(createdAt)
  if (Number.isNaN(then)) return 'Unknown'
  const seconds = Math.max(0, Math.floor((nowMs - then) / 1000))
  if (seconds < 60) return `${seconds} s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return minutes % 60 === 0 ? `${hours} h` : `${hours} h ${minutes % 60} min`
  return `${Math.floor(hours / 24)} d`
}

export function entityText(entityType: string, entityId: string): string {
  const id = entityId.includes(':') ? entityId.split(':').map(shortId).join(':') : shortId(entityId)
  return `${entityType} ${id}`
}

type Detail = Record<string, unknown>

function text(value: unknown): string {
  return value === null || value === undefined ? 'unknown' : String(value)
}

function sourceValue(entry: unknown): string {
  if (entry && typeof entry === 'object') {
    const { source, value } = entry as { source?: unknown; value?: unknown }
    return `${text(source)} ${text(value)}`
  }
  return text(entry)
}

/** One line that says what the rule saw. The full detail stays in the resolve panel. */
export function detailText(item: Pick<ReconciliationItem, 'kind' | 'detail'>): string {
  const d: Detail = item.detail
  switch (item.kind) {
    case 'STALE_EVENT':
      return `${text(d.type)} from ${text(d.source)} at ${text(d.occurredAt)} is older than the state held (${text(d.currentSource)}, ${text(d.currentOccurredAt)})`
    case 'SOURCE_CONFLICT':
      if (d.field === 'level') {
        const percent =
          d.differencePercent === null || d.differencePercent === undefined
            ? ''
            : ` (${text(d.differencePercent)} %)`
        return `${text(d.commodity)}: measured ${text(d.observed)} against book ${text(d.book)} ${text(d.unit)}${percent}`
      }
      return `${text(d.field)}: ${sourceValue(d.applied)} applied, ${sourceValue(d.other)} kept in the history${
        d.differenceMinutes === undefined ? '' : ` (${text(d.differenceMinutes)} min apart)`
      }`
    case 'NEGATIVE_BALANCE':
      return `${text(d.commodity)}: balance ${text(d.balance)} ${text(d.unit)}, a movement of ${text(d.delta)} was recorded but not applied`
    case 'CAPACITY_EXCEEDED':
      return `${text(d.commodity)}: stock ${text(d.stock)} ${text(d.unit)} above the usable capacity ${text(d.capacity)}`
  }
}

/** The cause of a dead letter without the reason its error starts with: `No cargo lot ...`. */
export function causeText(letter: Pick<DeadLetter, 'reason' | 'error'>): string {
  const prefix = `${letter.reason}: `
  return letter.error.startsWith(prefix) ? letter.error.slice(prefix.length) : letter.error
}
