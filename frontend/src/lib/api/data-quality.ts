import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'
import { apiRequest } from '@/lib/api-client'
import { defaultPortCode } from './hooks'
import { getParsed, parseResponse } from './validated'

/**
 * The Data quality panel (STATE-04): the items the projections opened for a person (stale events, source conflicts,
 * balances below zero or over capacity) and the events the projector gave up on. OPERATOR and ADMIN look and resolve;
 * only ADMIN replays a dead letter. Resolving records the decision only, it never changes data.
 */

export const RECONCILIATION_KINDS = [
  'STALE_EVENT',
  'SOURCE_CONFLICT',
  'NEGATIVE_BALANCE',
  'CAPACITY_EXCEEDED',
] as const
export type ReconciliationKind = (typeof RECONCILIATION_KINDS)[number]

export const reconciliationItemSchema = z.object({
  id: z.number().int(),
  kind: z.enum(RECONCILIATION_KINDS),
  /** The stream spelling: equipment, vessel-call, inventory, cargo-lot, gate-visit. */
  entityType: z.string(),
  entityId: z.string(),
  eventId: z.string().nullish(),
  /** The type of the event that opened the item, e.g. `equipment.position.observed`. */
  eventType: z.string().nullish(),
  detail: z.record(z.string(), z.unknown()),
  status: z.enum(['OPEN', 'RESOLVED']),
  createdAt: z.string().nullish(),
  resolvedBy: z.string().nullish(),
  resolvedAt: z.string().nullish(),
  note: z.string().nullish(),
})
export type ReconciliationItem = z.infer<typeof reconciliationItemSchema>

export const reconciliationItemsSchema = z.array(reconciliationItemSchema)

export const deadLetterStateSchema = z.enum(['OPEN', 'REPLAYED', 'REPLAY_FAILED'])
export type DeadLetterState = z.infer<typeof deadLetterStateSchema>

export const deadLetterSchema = z.object({
  id: z.number().int(),
  eventId: z.string().nullish(),
  consumer: z.string(),
  topic: z.string(),
  partition: z.number().int(),
  offset: z.number().int(),
  /** The first word of the error: UNKNOWN_ENTITY, UNIT_MISMATCH, PROCESSING_FAILED ... */
  reason: z.string(),
  error: z.string(),
  attempts: z.number().int(),
  createdAt: z.string().nullish(),
  /** Set on a replay attempt: the dead letter it is a new attempt of. */
  replayOf: z.number().int().nullish(),
  state: deadLetterStateSchema,
  replays: z.number().int(),
  lastReplayId: z.number().int().nullish(),
  type: z.string().nullish(),
  sourceSystem: z.string().nullish(),
  entityId: z.string().nullish(),
  event: z.record(z.string(), z.unknown()).nullish(),
})
export type DeadLetter = z.infer<typeof deadLetterSchema>

export const deadLettersSchema = z.array(deadLetterSchema)

export const replayOutcomeSchema = z.enum(['APPLIED', 'ALREADY_APPLIED', 'FAILED'])
export type ReplayOutcome = z.infer<typeof replayOutcomeSchema>

export const deadLetterReplaySchema = z.object({
  outcome: replayOutcomeSchema,
  attempt: deadLetterSchema,
})
export type DeadLetterReplay = z.infer<typeof deadLetterReplaySchema>

const basePath = (code: string) => `/v1/ports/${encodeURIComponent(code)}`

export const dataQualityKeys = {
  all: (code: string) => ['data-quality', code] as const,
  items: (code: string, kind: string) => ['data-quality', code, 'items', kind] as const,
  deadLetters: (code: string) => ['data-quality', code, 'dead-letters'] as const,
}

/** The queues change as events arrive: look again when the page is used and every half minute while it is open. */
const LIVE = { staleTime: 0, refetchInterval: 30_000 } as const

/** The open items, newest first; `kind` narrows to one kind (empty is every kind). */
export function useOpenReconciliationItems(kind: string = '', code: string = defaultPortCode()) {
  return useQuery({
    queryKey: dataQualityKeys.items(code, kind),
    queryFn: ({ signal }) =>
      getParsed(`${basePath(code)}/reconciliation-items`, reconciliationItemsSchema, {
        signal,
        query: { status: 'OPEN', kind: kind || undefined },
      }),
    ...LIVE,
  })
}

/** Records the human decision with its note. Nothing in the data changes: corrections are `inventory.adjusted` events. */
export function useResolveReconciliationItem(code: string = defaultPortCode()) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (input: { id: number; note: string }): Promise<ReconciliationItem> => {
      const path = `${basePath(code)}/reconciliation-items/${input.id}/resolve`
      const response = await apiRequest<unknown>(path, {
        method: 'POST',
        body: { note: input.note },
      })
      return parseResponse(path, reconciliationItemSchema, response.data)
    },
    onSettled: () => client.invalidateQueries({ queryKey: dataQualityKeys.all(code) }),
  })
}

/** The dead letters of the port, newest first, each with the state of its replays. */
export function useDeadLetters(code: string = defaultPortCode()) {
  return useQuery({
    queryKey: dataQualityKeys.deadLetters(code),
    queryFn: ({ signal }) =>
      getParsed(`${basePath(code)}/dead-letters`, deadLettersSchema, { signal }),
    ...LIVE,
  })
}

/** Applies the event again. The original is never changed: the result is a new attempt linked to it. ADMIN only. */
export function useReplayDeadLetter(code: string = defaultPortCode()) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: async (id: number): Promise<DeadLetterReplay> => {
      const path = `${basePath(code)}/dead-letters/${id}/replay`
      const response = await apiRequest<unknown>(path, { method: 'POST' })
      return parseResponse(path, deadLetterReplaySchema, response.data)
    },
    onSettled: () => client.invalidateQueries({ queryKey: dataQualityKeys.all(code) }),
  })
}
