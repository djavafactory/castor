import { toast } from 'sonner'
import { create } from 'zustand'
import { GOOGLE_TOAST, mayTraceOn } from '@/lib/basemap'
import type { components } from '@/types/api'
import { useUiStore } from './ui-store'

export type ProblemError = components['schemas']['ProblemError']

export type Point = [lon: number, lat: number]

export type EditorTool =
  | { kind: 'place'; typeCode: string; rotationDeg: number; points: Point[] }
  | { kind: 'draw'; typeCode: string; rotationDeg: number; points: Point[] }
  | { kind: 'measure'; typeCode: null; rotationDeg: number; points: Point[] }

export type ToolKind = EditorTool['kind']

/** One reversible edit (DESIGNER-08 defines the concrete commands). */
export interface HistoryEntry {
  label: string
  undo: () => void | Promise<void>
  redo: () => void | Promise<void>
}

export interface ValidationResult {
  valid: boolean
  errors: ProblemError[]
}

interface EditorState {
  tool: EditorTool | null
  undoStack: HistoryEntry[]
  redoStack: HistoryEntry[]
  /** Asset ids with a save in flight. */
  pendingSaves: string[]
  /** Asset ids whose last save failed and was not retried yet. */
  failedSaves: string[]
  lastValidation: ValidationResult | null
  /** The largest coordinate rounding (mm) any save of this session moved a shape by; shown by Check and save. */
  largestAdjustmentMm: number
  /** Counts Ctrl+S and Delete presses; the Inspector acts on each change. */
  saveRequest: number
  deleteRequest: number
  /** A Check and save is running; its message stays on screen until the next check. */
  checking: boolean
  checkMessage: string | null
  setTool: (kind: ToolKind, typeCode?: string) => void
  clearTool: () => void
  setRotation: (rotationDeg: number) => void
  addPoint: (point: Point) => void
  removeLastPoint: () => void
  resetPoints: () => void
  pushHistory: (entry: HistoryEntry) => void
  /** Moves the latest undo entry to the redo stack and returns it. */
  undo: () => HistoryEntry | undefined
  /** Moves the latest redo entry back to the undo stack and returns it. */
  redo: () => HistoryEntry | undefined
  clearHistory: () => void
  beginSave: (assetId: string) => void
  endSave: (assetId: string) => void
  /** Ends a save that failed: the asset moves from pending to failed. `retry` runs it again. */
  failSave: (assetId: string, retry?: () => void) => void
  /** Runs the retry of every failed save. */
  retryFailedSaves: () => void
  setValidation: (result: ValidationResult | null) => void
  noteAdjustment: (mm: number) => void
  requestSave: () => void
  requestDelete: () => void
  setChecking: (checking: boolean, checkMessage?: string | null) => void
  reset: () => void
}

/** What to run to try a failed save again; functions do not belong in reactive state. */
const retries = new Map<string, () => void>()

const initial = {
  tool: null,
  undoStack: [],
  redoStack: [],
  pendingSaves: [],
  failedSaves: [],
  lastValidation: null,
  largestAdjustmentMm: 0,
  saveRequest: 0,
  deleteRequest: 0,
  checking: false,
  checkMessage: null,
}

export const useEditorStore = create<EditorState>()((set, get) => ({
  ...initial,
  setTool: (kind, typeCode) => {
    // Google Maps is a locator only: nothing may be placed or drawn over it (measuring stores nothing)
    if (kind !== 'measure' && !mayTraceOn(useUiStore.getState().basemap)) {
      toast(GOOGLE_TOAST)
      return
    }
    set({
      tool:
        kind === 'measure'
          ? { kind, typeCode: null, rotationDeg: 0, points: [] }
          : { kind, typeCode: typeCode ?? '', rotationDeg: 0, points: [] },
    })
  },
  clearTool: () => set({ tool: null }),
  setRotation: (rotationDeg) =>
    set((state) => (state.tool ? { tool: { ...state.tool, rotationDeg } } : state)),
  addPoint: (point) =>
    set((state) =>
      state.tool ? { tool: { ...state.tool, points: [...state.tool.points, point] } } : state,
    ),
  removeLastPoint: () =>
    set((state) =>
      state.tool ? { tool: { ...state.tool, points: state.tool.points.slice(0, -1) } } : state,
    ),
  resetPoints: () => set((state) => (state.tool ? { tool: { ...state.tool, points: [] } } : state)),
  pushHistory: (entry) =>
    // A new edit invalidates anything that could have been redone.
    set((state) => ({ undoStack: [...state.undoStack, entry], redoStack: [] })),
  undo: () => {
    const entry = get().undoStack.at(-1)
    if (!entry) return undefined
    set((state) => ({
      undoStack: state.undoStack.slice(0, -1),
      redoStack: [...state.redoStack, entry],
    }))
    return entry
  },
  redo: () => {
    const entry = get().redoStack.at(-1)
    if (!entry) return undefined
    set((state) => ({
      redoStack: state.redoStack.slice(0, -1),
      undoStack: [...state.undoStack, entry],
    }))
    return entry
  },
  clearHistory: () => set({ undoStack: [], redoStack: [] }),
  beginSave: (assetId) => {
    retries.delete(assetId)
    set((state) => ({
      pendingSaves: [...state.pendingSaves, assetId],
      failedSaves: state.failedSaves.filter((id) => id !== assetId),
    }))
  },
  endSave: (assetId) =>
    set((state) => {
      const index = state.pendingSaves.indexOf(assetId)
      if (index < 0) return state
      return { pendingSaves: state.pendingSaves.filter((_, i) => i !== index) }
    }),
  failSave: (assetId, retry) => {
    if (retry) retries.set(assetId, retry)
    set((state) => ({
      pendingSaves: state.pendingSaves.filter((id) => id !== assetId),
      failedSaves: state.failedSaves.includes(assetId)
        ? state.failedSaves
        : [...state.failedSaves, assetId],
    }))
  },
  retryFailedSaves: () => {
    const run = get()
      .failedSaves.map((id) => retries.get(id))
      .filter((retry) => retry !== undefined)
    for (const retry of run) retry()
  },
  setValidation: (lastValidation) => set({ lastValidation }),
  noteAdjustment: (mm) =>
    set((state) => (mm > state.largestAdjustmentMm ? { largestAdjustmentMm: mm } : state)),
  requestSave: () => set((state) => ({ saveRequest: state.saveRequest + 1 })),
  requestDelete: () => set((state) => ({ deleteRequest: state.deleteRequest + 1 })),
  setChecking: (checking, checkMessage) =>
    set((state) => ({
      checking,
      checkMessage: checkMessage === undefined ? state.checkMessage : checkMessage,
    })),
  reset: () => {
    retries.clear()
    set({ ...initial })
  },
}))
