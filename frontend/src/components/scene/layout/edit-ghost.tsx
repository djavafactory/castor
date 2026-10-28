import { useThree } from '@react-three/fiber'
import { useEffect, useMemo } from 'react'
import { BufferGeometry, Line, LineBasicMaterial, Vector3 } from 'three'
import { useEditPreview } from '@/features/design/edit/edit-preview-store'
import { hex, palette } from '@/features/scene/palette'

/** Lifted a little so the outline is not buried in the ground. */
const GHOST_HEIGHT_M = 0.3

/** In Split view the 3D scene mirrors a 2D drag: the outline of the shape being moved, orange, or red when refused. */
export function EditGhost() {
  const preview = useEditPreview((s) => s.preview)
  const invalidate = useThree((s) => s.invalidate)

  const line = useMemo(() => {
    if (!preview) return null
    const ring = preview.rings[0]
    if (!ring || ring.length < 2) return null
    // Scene X = local x, Z = -local y
    const points = ring.map(([x, y]) => new Vector3(x, GHOST_HEIGHT_M, -y))
    if (preview.closed) points.push(points[0]!.clone())
    const material = new LineBasicMaterial({
      color: hex(preview.valid ? palette.signal : palette.brick),
      depthTest: false,
    })
    const object = new Line(new BufferGeometry().setFromPoints(points), material)
    object.name = 'edit-ghost'
    object.renderOrder = 6
    object.raycast = () => null
    return object
  }, [preview])

  useEffect(() => {
    invalidate()
    return () => {
      line?.geometry.dispose()
      ;(line?.material as LineBasicMaterial | undefined)?.dispose()
    }
  }, [line, invalidate])

  return line ? <primitive object={line} /> : null
}
