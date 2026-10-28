import { create } from 'zustand'
import type { Local } from '@/lib/geo'
import type { EditShape } from './edit-model'

/** The shape being dragged, as the 2D map draws it and the 3D ghost mirrors it. */
export interface EditPreview {
  assetId: string
  shape: EditShape
  rings: Local[][]
  closed: boolean
  /** False when a rule refuses the position: it is drawn faded with a red outline. */
  valid: boolean
  reason: string | null
  /** The height being dragged in 3D, metres; absent when the height does not change. */
  heightM?: number
  /** The view the drag happens in: the reason tooltip follows the pointer there. 2D when absent. */
  view?: '2d' | '3d'
  /** Where the pointer is on the canvas, in pixels, for the reason tooltip. */
  px: number
  py: number
}

interface PreviewState {
  preview: EditPreview | null
  setPreview: (preview: EditPreview | null) => void
}

export const useEditPreview = create<PreviewState>()((set) => ({
  preview: null,
  setPreview: (preview) => set({ preview }),
}))
