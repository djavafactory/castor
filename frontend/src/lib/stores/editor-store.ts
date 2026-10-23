import { create } from 'zustand'
import type { components } from '@/types/api'

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
  lastValidation: ValidationResult | null
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
  setValidation: (result: ValidationResult | null) => void
  reset: () => void
}

const initial = {
  tool: null,
  undoStack: [],
  redoStack: [],
  pendingSaves: [],
  lastValidation: null,
}

export const useEditorStore = create<EditorState>()((set, get) => ({
  ...initial,
  setTool: (kind, typeCode) =>
    set({
      tool:
        kind === 'measure'
          ? { kind, typeCode: null, rotationDeg: 0, points: [] }
          : { kind, typeCode: typeCode ?? '', rotationDeg: 0, points: [] },
    }),
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
  beginSave: (assetId) => set((state) => ({ pendingSaves: [...state.pendingSaves, assetId] })),
  endSave: (assetId) =>
    set((state) => {
      const index = state.pendingSaves.indexOf(assetId)
      if (index < 0) return state
      return { pendingSaves: state.pendingSaves.filter((_, i) => i !== index) }
    }),
  setValidation: (lastValidation) => set({ lastValidation }),
  reset: () => set({ ...initial }),
}))
