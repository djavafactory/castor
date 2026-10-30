import { create } from 'zustand'
import {
  type EntityByType,
  type EntityType,
  type StateDelta,
  type StateSnapshot,
  parseEntity,
} from './protocol'
import { type ServerClockReading, serverNowMs } from './clock'

export type ConnectionStatus = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'offline'

type Table<T extends EntityType> = Record<string, EntityByType[T]>

interface LiveState {
  status: ConnectionStatus
  /** Number of the current reconnect attempt (1 for the first retry); 0 while live or on the first connect. */
  attempt: number
  /** Wall clock (epoch ms) of the last message of the server, or null before the first. */
  lastMessageAt: number | null
  /** Sequence of the last applied delta (the snapshot's until the first delta). */
  sequence: number
  clock: ServerClockReading | null
  equipment: Table<'equipment'>
  vesselCalls: Table<'vessel-call'>
  inventories: Table<'inventory'>
  gateVisits: Table<'gate-visit'>
  openGateVisits: number
  openReconciliationItems: number

  setConnection: (status: ConnectionStatus, attempt: number) => void
  touch: (at: number) => void
  setClock: (clock: ServerClockReading) => void
  applySnapshot: (snapshot: StateSnapshot, clock: ServerClockReading | null) => void
  /** Applies a delta unless the stored entity is already at that version or newer. Returns true when it changed. */
  applyDelta: (delta: StateDelta) => boolean
  setSequence: (sequence: number) => void
  reset: () => void
}

const empty = {
  status: 'idle' as ConnectionStatus,
  attempt: 0,
  lastMessageAt: null as number | null,
  sequence: 0,
  clock: null as ServerClockReading | null,
  equipment: {} as Table<'equipment'>,
  vesselCalls: {} as Table<'vessel-call'>,
  inventories: {} as Table<'inventory'>,
  gateVisits: {} as Table<'gate-visit'>,
  openGateVisits: 0,
  openReconciliationItems: 0,
}

const byId = <T extends { id: string }>(items: T[]): Record<string, T> =>
  Object.fromEntries(items.map((item) => [item.id, item]))

const TABLE_OF = {
  equipment: 'equipment',
  'vessel-call': 'vesselCalls',
  inventory: 'inventories',
  'gate-visit': 'gateVisits',
} as const

/** The live operational state of the port in this tab, keyed by entity id. Memory only. */
export const useLiveStore = create<LiveState>()((set, get) => ({
  ...empty,
  setConnection: (status, attempt) => set({ status, attempt }),
  touch: (at) => set({ lastMessageAt: at }),
  setClock: (clock) => set({ clock }),
  applySnapshot: (snapshot, clock) =>
    set({
      sequence: snapshot.sequence,
      clock: clock ?? get().clock,
      equipment: byId(snapshot.equipment),
      vesselCalls: byId(snapshot.vesselCalls),
      inventories: byId(snapshot.inventories),
      // The snapshot carries only the count of open gate visits; visits arrive as deltas
      gateVisits: {},
      openGateVisits: snapshot.openGateVisits,
      openReconciliationItems: snapshot.openReconciliationItems,
    }),
  applyDelta: (delta) => {
    const type = delta.entityType
    if (!(type in TABLE_OF)) return false
    const key = TABLE_OF[type as EntityType]
    const entity = parseEntity(type as EntityType, delta.data)
    if (!entity) return false
    const table = get()[key] as Record<string, { version: number }>
    const stored = table[delta.entityId]
    if (stored && delta.entityVersion <= stored.version) return false
    set({
      [key]: { ...table, [delta.entityId]: { ...entity, version: delta.entityVersion } },
    } as Partial<LiveState>)
    return true
  },
  setSequence: (sequence) => set({ sequence }),
  reset: () => set({ ...empty }),
}))

/** The server's time now (epoch ms), or null before the first snapshot. */
export function serverNow(perfNow: number = performance.now()): number | null {
  const clock = useLiveStore.getState().clock
  return clock ? serverNowMs(clock, perfNow) : null
}
