/**
 * Reduced motion (JOIN-03): with the OS setting on there is no auto-rotate, no camera fly, no hover pulse and no
 * water motion. CSS transitions and animations are cut in index.css.
 */
export function prefersReducedMotion(): boolean {
  return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** The toast that refuses auto-rotate; one at a time however often R is pressed. */
export const ROTATE_REFUSED = 'Auto-rotate is off because reduced motion is on'
export const ROTATE_REFUSED_ID = 'rotate-reduced-motion'
