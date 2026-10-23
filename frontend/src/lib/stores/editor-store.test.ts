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
