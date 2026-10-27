import { useEffect, useMemo } from 'react'
import { CAPTION_PX, CAPTION_SCALE, labelTexture } from '../models/asset-visual'
import { buildYard, disposeYard } from './container-instances'
import { BOX_HEIGHT, type YardSpec } from './container-stacks'

/** "1,234 TEU" under the yard code. */
export function yardCaption(code: string, teu: number): string {
  return `${code} · ${teu.toLocaleString('en-US')} TEU`
}

/**
 * A container yard standing on the ground, footprint centred on the parent's origin, length along +X.
 * The boxes are static design fill, rebuilt only when the yard's code, size, tiers or fill change.
 */
export function ContainerYard({ code, length, width, maxTiers, designFill }: YardSpec) {
  const built = useMemo(
    () => buildYard({ code, length, width, maxTiers, designFill }),
    [code, length, width, maxTiers, designFill],
  )
  useEffect(() => () => disposeYard(built.group), [built])
  const text = yardCaption(code, built.teu)
  const texture = useMemo(() => labelTexture(text, CAPTION_PX), [text])
  useEffect(() => () => texture?.dispose(), [texture])
  return (
    <>
      <primitive object={built.group} />
      {texture && (
        <sprite name="caption" position={[0, maxTiers * BOX_HEIGHT + 4, 0]} scale={CAPTION_SCALE}>
          <spriteMaterial map={texture} depthTest={false} transparent />
        </sprite>
      )}
    </>
  )
}
