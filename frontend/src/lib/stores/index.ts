/**
 * Performance rule: 3D components read high-frequency values with `useStore.getState()` inside
 * `useFrame` or with transient subscriptions (`useStore.subscribe`). React panels use selectors
 * with shallow equality (`useShallow`) so they re-render only when what they show changes.
 */
export { useShallow } from 'zustand/react/shallow'
export { useAuthStore, basicCredential } from './auth-store'
export { useEditorStore } from './editor-store'
export { useSelectionStore } from './selection-store'
export { useUiStore } from './ui-store'
