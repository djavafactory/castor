import { z } from 'zod'

// Wire types of the STATE-02 live stream (README, "Live stream contract"). Hand-written on purpose: the OpenAPI
// snapshot does not describe a WebSocket, and these parsers also guard the browser against a malformed message.

const text = z.string().nullish()
const num = z.number().nullish()

const itemBase = {
  id: z.string(),
  quality: text,
  source: text,
  observedAt: text,
  /** R3: the SLA in seconds. FRESH or STALE is computed in the browser from `observedAt` and the server clock. */
  freshnessSlaS: num,
  version: z.number(),
}

export const equipmentSchema = z.object({
  ...itemBase,
  assetId: text,
  status: text,
  statusReason: text,
  position: z
    .object({
      lon: z.number(),
      lat: z.number(),
      headingDeg: num,
      accuracyM: num,
      observedAt: text,
    })
    .nullish(),
  assignment: z.unknown().optional(),
  lastOccurredAt: text,
})

export const vesselCallSchema = z.object({
  ...itemBase,
  callCode: text,
  vesselName: text,
  imo: text,
  loaM: num,
  beamM: num,
  status: text,
  eta: text,
  ata: text,
  etd: text,
  atd: text,
  berthAssetId: text,
})

/** A mass in tonnes: UNKNOWN has no value (R3), a volume needs a density to have one. */
const massSchema = z.object({ status: z.string(), tonnes: num })

export const inventorySchema = z.object({
  ...itemBase,
  storageAssetId: text,
  commodity: text,
  /** The book balance; null while no movement was recorded (unknown, never 0). */
  quantity: num,
  unit: text,
  massBasis: text,
  /** A movement that would have gone below zero left the balance as it was: a person has to look. */
  needsReconciliation: z.boolean().optional(),
  /** The usable capacity of the area in the published layout; `status` is KNOWN or UNKNOWN. */
  capacity: z.object({ status: z.string(), value: num, unit: text }).nullish(),
  /** What a tank or silo sensor last read, next to the book balance and never in place of it. */
  observed: z
    .object({
      quantity: num,
      unit: text,
      observedAt: text,
      source: text,
      quality: text,
      densityKgM3: num,
      temperatureC: num,
    })
    .nullish(),
  bookMass: massSchema.nullish(),
  observedMass: massSchema.nullish(),
})

export const gateVisitSchema = z.object({
  ...itemBase,
  truckVisitId: text,
  gateAssetId: text,
  inAt: text,
  outAt: text,
  lane: text,
})

export type EquipmentState = z.infer<typeof equipmentSchema>
export type VesselCall = z.infer<typeof vesselCallSchema>
export type Inventory = z.infer<typeof inventorySchema>
export type GateVisit = z.infer<typeof gateVisitSchema>

export const ENTITY_TYPES = ['equipment', 'vessel-call', 'inventory', 'gate-visit'] as const
export type EntityType = (typeof ENTITY_TYPES)[number]

export interface EntityByType {
  equipment: EquipmentState
  'vessel-call': VesselCall
  inventory: Inventory
  'gate-visit': GateVisit
}

const itemSchemas = {
  equipment: equipmentSchema,
  'vessel-call': vesselCallSchema,
  inventory: inventorySchema,
  'gate-visit': gateVisitSchema,
} as const

export function isEntityType(value: string): value is EntityType {
  return (ENTITY_TYPES as readonly string[]).includes(value)
}

/** One snapshot item of a known entity type (the `data` of a delta), or null when it does not fit. */
export function parseEntity<T extends EntityType>(type: T, data: unknown): EntityByType[T] | null {
  const result = itemSchemas[type].safeParse(data)
  return result.success ? (result.data as EntityByType[T]) : null
}

export const snapshotSchema = z.object({
  sequence: z.number().int().min(0),
  serverTime: z.string(),
  clockSpeed: z.number().positive(),
  equipment: z.array(equipmentSchema),
  vesselCalls: z.array(vesselCallSchema),
  inventories: z.array(inventorySchema),
  openGateVisits: z.number().int().min(0),
  openReconciliationItems: z.number().int().min(0),
})
export type StateSnapshot = z.infer<typeof snapshotSchema>

export const ticketSchema = z.object({ ticket: z.string().min(1), expiresAt: z.string() })
export type StreamTicket = z.infer<typeof ticketSchema>

export const deltaSchema = z.object({
  type: z.literal('delta'),
  sequence: z.number().int(),
  entityType: z.string(),
  entityId: z.string(),
  entityVersion: z.number(),
  occurredAt: z.string(),
  data: z.unknown(),
})
export type StateDelta = z.infer<typeof deltaSchema>

export const heartbeatSchema = z.object({
  type: z.literal('heartbeat'),
  sequence: z.number().int(),
  serverTime: z.string(),
  clockSpeed: z.number().positive(),
})
export type Heartbeat = z.infer<typeof heartbeatSchema>

export type ServerMessage = StateDelta | Heartbeat

/** Parses one text frame; null for anything that is not a known, well-formed message. */
export function parseServerMessage(raw: unknown): ServerMessage | null {
  if (typeof raw !== 'string') return null
  let json: unknown
  try {
    json = JSON.parse(raw)
  } catch {
    return null
  }
  const delta = deltaSchema.safeParse(json)
  if (delta.success) return delta.data
  const heartbeat = heartbeatSchema.safeParse(json)
  return heartbeat.success ? heartbeat.data : null
}

/** Close code the server uses when the send queue overflowed: fetch a fresh snapshot. */
export const CLOSE_RESYNC = 4001
