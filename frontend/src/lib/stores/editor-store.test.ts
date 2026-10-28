import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useEditorStore, type HistoryEntry } from './editor-store'

const entry = (label: string): HistoryEntry => ({ label, undo: vi.fn(), redo: vi.fn() })

beforeEach(() => useEditorStore.getState().reset())

describe('useEditorStore', () => {
  it('starts with no tool, empty history and no validation', () => {
    expect(useEditorStore.getState()).toMatchObject({
      tool: null,
      undoStack: [],
      redoStack: [],
      pendingSaves: [],
      lastValidation: null,
    })
  })

  it('manages the active tool, rotation and points', () => {
    const editor = useEditorStore.getState()
    editor.setTool('place', 'QUAY_CRANE')
    editor.setRotation(45)
    editor.addPoint([101.3, 2.95])
    editor.addPoint([101.31, 2.96])
    expect(useEditorStore.getState().tool).toEqual({
      kind: 'place',
      typeCode: 'QUAY_CRANE',
      rotationDeg: 45,
      points: [
        [101.3, 2.95],
        [101.31, 2.96],
      ],
    })

    editor.removeLastPoint()
    expect(useEditorStore.getState().tool?.points).toHaveLength(1)
    editor.resetPoints()
    expect(useEditorStore.getState().tool?.points).toEqual([])

    editor.setTool('measure')
    expect(useEditorStore.getState().tool).toMatchObject({ kind: 'measure', typeCode: null })

    editor.clearTool()
    expect(useEditorStore.getState().tool).toBeNull()
  })

  it('ignores point edits when no tool is active', () => {
    useEditorStore.getState().addPoint([1, 2])
    expect(useEditorStore.getState().tool).toBeNull()
  })

  it('undoes and redoes, and a new edit drops the redo stack', () => {
    const editor = useEditorStore.getState()
    const a = entry('a')
    const b = entry('b')
    editor.pushHistory(a)
    editor.pushHistory(b)

    expect(editor.undo()).toBe(b)
    expect(useEditorStore.getState().undoStack).toEqual([a])
    expect(useEditorStore.getState().redoStack).toEqual([b])

    expect(editor.redo()).toBe(b)
    expect(useEditorStore.getState().redoStack).toEqual([])

    editor.undo()
    editor.pushHistory(entry('c'))
    expect(useEditorStore.getState().redoStack).toEqual([])
  })

  it('returns undefined when there is nothing to undo or redo', () => {
    expect(useEditorStore.getState().undo()).toBeUndefined()
    expect(useEditorStore.getState().redo()).toBeUndefined()
  })

  it('keeps at most 100 undo steps, dropping the oldest', () => {
    const editor = useEditorStore.getState()
    for (let i = 0; i < 105; i++) editor.pushHistory(entry(`${i}`))
    const stack = useEditorStore.getState().undoStack
    expect(stack).toHaveLength(100)
    expect(stack[0]?.label).toBe('5')
    expect(stack.at(-1)?.label).toBe('104')
  })

  it('logs every pushed step, newest first, capped at 60', () => {
    const editor = useEditorStore.getState()
    for (let i = 0; i < 65; i++) editor.pushHistory(entry(`${i}`))
    const log = useEditorStore.getState().historyLog
    expect(log).toHaveLength(60)
    expect(log[0]).toMatchObject({ label: '64' })
    expect(log.at(-1)).toMatchObject({ label: '5' })
  })

  it('logHistory adds a line without touching the undo/redo stacks', () => {
    const editor = useEditorStore.getState()
    editor.logHistory('Basemap: None (grid)')
    expect(useEditorStore.getState().historyLog[0]).toMatchObject({ label: 'Basemap: None (grid)' })
    expect(useEditorStore.getState().undoStack).toEqual([])
  })

  it('discardFromRedo drops a step a failed undo moved to redo', () => {
    const editor = useEditorStore.getState()
    const a = entry('a')
    editor.pushHistory(a)
    const moved = editor.undo()! // undoStack: [], redoStack: [a], as runUndo does before the call fails
    editor.discardFromRedo(moved)
    expect(useEditorStore.getState().redoStack).toEqual([])
    expect(useEditorStore.getState().undoStack).toEqual([])
  })

  it('discardFromUndo drops a step a failed redo moved to undo', () => {
    const editor = useEditorStore.getState()
    const a = entry('a')
    editor.pushHistory(a)
    editor.undo() // undoStack: [], redoStack: [a]
    const moved = editor.redo()! // undoStack: [a], redoStack: [], as runRedo does before the call fails
    editor.discardFromUndo(moved)
    expect(useEditorStore.getState().undoStack).toEqual([])
  })

  it('tracks pending saves per asset, counting overlapping saves', () => {
    const editor = useEditorStore.getState()
    editor.beginSave('a')
    editor.beginSave('a')
    editor.beginSave('b')
    editor.endSave('a')
    expect(useEditorStore.getState().pendingSaves).toEqual(['a', 'b'])
    editor.endSave('missing')
    expect(useEditorStore.getState().pendingSaves).toEqual(['a', 'b'])
  })

  it('stores the last validation result', () => {
    const result = {
      valid: false,
      errors: [{ code: 'TOO_SMALL', message: 'The outline is smaller than 4 m2' }],
    }
    useEditorStore.getState().setValidation(result)
    expect(useEditorStore.getState().lastValidation).toEqual(result)
  })
})
