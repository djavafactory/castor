import { useEffect, useMemo } from 'react'
import { hex, palette } from '@/features/scene/palette'
import { buildEnvelopeGeometry, buildOutlineBatches, DASH_M, GAP_M } from './debug-geometry'
import type { AccuracyOutline, PickBox } from './placement'

/** Orange wireframe boxes: footprint x height of every asset, so a wrongly scaled model stands out. */
export function EnvelopeLayer({ boxes }: { boxes: readonly PickBox[] }) {
  const geometry = useMemo(() => buildEnvelopeGeometry(boxes), [boxes])
  useEffect(() => () => geometry?.dispose(), [geometry])
  if (!geometry) return null
  return (
    <lineSegments name="envelope" geometry={geometry} raycast={() => null} renderOrder={5}>
      <lineBasicMaterial color={hex(palette.signal)} />
    </lineSegments>
  )
}

/** Footprint outlines in the accuracy colours; APPROXIMATE ones are dashed. */
export function AccuracyOutlines({ outlines }: { outlines: readonly AccuracyOutline[] }) {
  const batches = useMemo(() => buildOutlineBatches(outlines), [outlines])
  useEffect(() => () => batches.forEach((b) => b.geometry.dispose()), [batches])
  return (
    <group name="accuracy-outlines">
      {batches.map((b) => {
        const colour = hex(
          palette.accuracy[b.accuracy as keyof typeof palette.accuracy] ??
            palette.accuracy.APPROXIMATE,
        )
        return (
          <lineSegments
            key={b.accuracy}
            name={`accuracy:${b.accuracy}`}
            geometry={b.geometry}
            raycast={() => null}
            renderOrder={4}
            onUpdate={b.dashed ? (self) => self.computeLineDistances() : undefined}
          >
            {b.dashed ? (
              <lineDashedMaterial color={colour} dashSize={DASH_M} gapSize={GAP_M} />
            ) : (
              <lineBasicMaterial color={colour} />
            )}
          </lineSegments>
        )
      })}
    </group>
  )
}
