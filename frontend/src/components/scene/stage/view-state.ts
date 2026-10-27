import { Vector3 } from 'three'
import { create } from 'zustand'

/** What the camera looks at right now; written every frame by the camera rig, read by light, fog and imagery. */
export const view = { target: new Vector3(0, 0, -150), distance: 1500 }

export type CameraCommand =
  | { id: number; type: 'preset'; key: string }
  | { id: number; type: 'whole' }
  | { id: number; type: 'zoom'; factor: number }
  | { id: number; type: 'focus'; centre: [number, number, number]; radius: number }

type NewCommand = CameraCommand extends infer C
  ? C extends { id: number }
    ? Omit<C, 'id'>
    : never
  : never

interface CameraState {
  autoRotate: boolean
  /** The latest command; the rig runs it once (by id). */
  command: CameraCommand | null
  /** True when the camera is close enough for the sharper imagery zoom. */
  near: boolean
  issue: (command: NewCommand) => void
  setAutoRotate: (on: boolean) => void
  toggleRotate: () => void
  /** Rotate off + Overview. */
  reset: () => void
  setNear: (near: boolean) => void
}

let seq = 0

export const useCameraStore = create<CameraState>()((set) => ({
  autoRotate: false,
  command: null,
  near: false,
  issue: (command) => set({ command: { ...command, id: ++seq } as CameraCommand }),
  setAutoRotate: (autoRotate) => set({ autoRotate }),
  toggleRotate: () => set((s) => ({ autoRotate: !s.autoRotate })),
  reset: () => set({ autoRotate: false, command: { id: ++seq, type: 'preset', key: 'overview' } }),
  setNear: (near) => set({ near }),
}))

/** Camera actions for buttons, keys and later tickets (focusOn frames an asset's footprint). */
export const camera = {
  preset: (key: string) => useCameraStore.getState().issue({ type: 'preset', key }),
  wholePort: () => useCameraStore.getState().issue({ type: 'whole' }),
  zoomIn: () => useCameraStore.getState().issue({ type: 'zoom', factor: 1 / 1.35 }),
  zoomOut: () => useCameraStore.getState().issue({ type: 'zoom', factor: 1.35 }),
  toggleRotate: () => useCameraStore.getState().toggleRotate(),
  reset: () => useCameraStore.getState().reset(),
  /** Frames the footprint circle of an asset (centre in scene metres). */
  focusOn: (centre: [number, number, number], radius: number) =>
    useCameraStore.getState().issue({ type: 'focus', centre, radius }),
}
