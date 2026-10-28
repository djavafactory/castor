import { useCallback, useEffect, useMemo, useRef } from 'react'
import { toast } from 'sonner'
import { create } from 'zustand'
import { useMapStore } from '@/components/map/map-store'
import {
  useAssetTypes,
  useCreateAsset,
  useDeleteAsset,
  useDraftLayout,
  usePort,
  type AssetType,
  type LayoutAsset,
} from '@/lib/api'
import { ApiError } from '@/lib/api-client'
import { GOOGLE_TOAST, mayTraceOn } from '@/lib/basemap'
import { hasActiveFrame, type Local } from '@/lib/geo'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { nextIdentity } from '../draw/draw-rules'
import { placementBody, planPlacement, type PlacementPlan } from './placement-plan'
import {
  draftShapes,
  placementProblem,
  siteOf,
  type Candidate,
  type DraftShape,
  type PlacementSite,
} from './placement-rules'

/** The ghost: the plan under the pointer and why it cannot be placed there (null when it can). */
interface PlacementState {
  plan: PlacementPlan | null
  problem: string | null
  /** A palette tile being dragged: the type follows the pointer until it is dropped. */
  dragging: AssetType | null
  /** Where the pointer is on screen, for the label chip and the pill. */
  pointer: { x: number; y: number } | null
  set: (plan: PlacementPlan | null, problem: string | null) => void
  setDragging: (type: AssetType | null) => void
  setPointer: (pointer: { x: number; y: number } | null) => void
}

export const usePlacementStore = create<PlacementState>()((set) => ({
  plan: null,
  problem: null,
  dragging: null,
  pointer: null,
  set: (plan, problem) => set({ plan, problem }),
  setDragging: (dragging) => set({ dragging }),
  setPointer: (pointer) => set({ pointer }),
}))

/** Set by the mounted ghost: places the ghost of a dragged tile. */
export const dropHandler: { current: (() => Promise<boolean>) | null } = { current: null }

export const VALID_HINT = 'Click to place. R rotates, Shift keeps the tool, Esc cancels.'
export const cannotPlace = (reason: string) => `Cannot place here: ${reason}`

/** The type being placed: the tile being dragged, else the type of the active place tool. */
function activeType(types: ReadonlyMap<string, AssetType>): AssetType | null {
  const dragging = usePlacementStore.getState().dragging
  if (dragging) return dragging
  const tool = useEditorStore.getState().tool
  return tool?.kind === 'place' ? (types.get(tool.typeCode) ?? null) : null
}

/**
 * What the rules and plans need, kept current in a ref so pointer events never wait for a render. The outlines are
 * worked out on first use (the port frame is only active once the map has set it up) and kept until the data changes.
 */
interface Inputs {
  port: ReturnType<typeof usePort>['data']
  assets: readonly LayoutAsset[]
  types: Map<string, AssetType>
  derived?: { site: PlacementSite; shapes: DraftShape[] }
}

function inputsFor(
  port: ReturnType<typeof usePort>['data'],
  assets: readonly LayoutAsset[] | undefined,
  typeList: readonly AssetType[] | undefined,
): Inputs {
  return { port, assets: assets ?? [], types: new Map((typeList ?? []).map((t) => [t.code, t])) }
}

function derive(inputs: Inputs): { site: PlacementSite; shapes: DraftShape[] } | null {
  if (!inputs.port || !hasActiveFrame()) return null
  inputs.derived ??= { site: siteOf(inputs.port), shapes: draftShapes(inputs.assets) }
  return inputs.derived
}

function compute(inputs: Inputs, cursor: Local, rotationDeg: number): void {
  const type = activeType(inputs.types)
  const world = derive(inputs)
  if (!type || !world) {
    usePlacementStore.getState().set(null, null)
    return
  }
  const { site, shapes } = world
  const plan = planPlacement(type, cursor, rotationDeg, site, shapes, inputs.types)
  const candidate: Candidate = {
    name: type.label,
    type,
    ring: plan.ring,
    line: plan.line,
  }
  usePlacementStore.getState().set(plan, placementProblem(candidate, site, inputs.types, shapes))
}

/**
 * Keeps the ghost current while a place tool is active or a tile is being dragged: the pointer (2D map or 3D ground),
 * the rotation and the draft decide the plan and its validity. Mount once (R and Esc are keys of the shell).
 */
export function usePlacementGhost() {
  const place = usePlaceAsset()
  useEffect(() => {
    dropHandler.current = () => place({ keepTool: false })
    return () => {
      dropHandler.current = null
    }
  }, [place])
  const port = usePort()
  const draft = useDraftLayout()
  const types = useAssetTypes()
  const inputs = useRef<Inputs>(inputsFor(undefined, undefined, undefined))
  const current = useMemo(
    () => inputsFor(port.data, draft.data?.assets, types.data),
    [port.data, draft.data?.assets, types.data],
  )
  useEffect(() => {
    inputs.current = current
  }, [current])

  useEffect(() => {
    const refresh = () => {
      const cursor = useMapStore.getState().cursor
      const tool = useEditorStore.getState().tool
      if (!cursor || (!usePlacementStore.getState().dragging && tool?.kind !== 'place')) {
        usePlacementStore.getState().set(null, null)
        return
      }
      compute(inputs.current, [cursor.x, cursor.y], tool?.rotationDeg ?? 0)
    }
    refresh()
    const unsubscribeMap = useMapStore.subscribe((s, prev) => {
      if (s.cursor !== prev.cursor) refresh()
    })
    const unsubscribeEditor = useEditorStore.subscribe((s, prev) => {
      if (s.tool !== prev.tool) refresh()
    })
    const unsubscribePlacement = usePlacementStore.subscribe((s, prev) => {
      if (s.dragging !== prev.dragging) refresh()
    })
    return () => {
      unsubscribeMap()
      unsubscribeEditor()
      unsubscribePlacement()
    }
  }, [port.data, draft.data, types.data])
}

const reasonOf = (error: ApiError) => error.errors[0]?.message ?? error.detail ?? error.title

/**
 * Places the asset of the ghost: checks the rules again, saves it (POST asset), selects it and puts "Placed <name>" on
 * the undo stack. An invalid spot or a refusal of the server shows "Cannot place here: <reason>" and stores nothing.
 */
export function usePlaceAsset() {
  const draft = useDraftLayout()
  const create = useCreateAsset()
  const remove = useDeleteAsset()
  const { mutateAsync: createAsset } = create
  const { mutateAsync: removeAsset } = remove
  const assets = draft.data?.assets

  return useCallback(
    async (options: { keepTool: boolean }): Promise<boolean> => {
      const { plan, problem } = usePlacementStore.getState()
      if (!plan) return false
      if (!mayTraceOn(useUiStore.getState().basemap)) {
        toast(cannotPlace(GOOGLE_TOAST))
        return false
      }
      if (problem) {
        toast(cannotPlace(problem))
        return false
      }
      const name = nextIdentity(plan.type, assets ?? []).name
      const body = placementBody(plan, name)
      let created: LayoutAsset
      try {
        created = await createAsset(body)
      } catch (error) {
        if (error instanceof ApiError && (error.status === 400 || error.status === 422)) {
          toast(cannotPlace(reasonOf(error)))
        }
        return false
      }
      let current = created
      useSelectionStore.getState().select(current.id)
      useEditorStore.getState().pushHistory({
        label: `Placed ${name}`,
        assetName: name,
        undo: async () => {
          await removeAsset({ id: current.id, rowVersion: current.rowVersion })
        },
        redo: async () => {
          // the same id and code come back (re-create, DESIGNER-04 5b)
          current = await createAsset({ ...body, id: current.id, code: String(current.code ?? '') })
        },
      })
      if (!options.keepTool) useEditorStore.getState().clearTool()
      return true
    },
    [assets, createAsset, removeAsset],
  )
}
