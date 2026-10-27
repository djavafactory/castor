import { useEffect, useMemo } from 'react'
import { CanvasTexture, SRGBColorSpace } from 'three'
import { matteMaterial } from './box-batch'
import type { Pt } from './box-batch'
import { buildModel, disposeModel, getModelSpec, modelScale } from './registry'
import {
  COLOUR,
  RING_OPACITY,
  type ModelDetails,
  type ModelParams,
  type ModelSize,
} from './model-spec'

export interface AssetVisualProps {
  /** The asset's model_key. */
  modelKey: string
  /** Asset name: shown on the neutral box of an unknown model. */
  label?: string
  /** The asset's size. Changing it rescales the model at once; nothing is rebuilt. */
  size: ModelSize
  /** PARKED mobile assets get the uncertainty ring. */
  positionMode?: string
  /** Position uncertainty in metres: radius of the ring of a parked mobile asset. */
  uncertaintyM?: number
  /** Drawn polygon of an area asset and polyline of a linear one, around the asset's centre, metres. */
  footprint?: Pt[]
  path?: Pt[]
  /** Asset details the shape depends on: doors, bays, design fill, berth name. */
  details?: ModelDetails
}

/** Pixel size of the label texture on an unknown model. */
const LABEL_PX = 256

/** Captions above models (berth and pad names) are wider than the label of an unknown model. */
export const CAPTION_PX = 512
export const CAPTION_SCALE: [number, number, number] = [32, 4, 1]

export function labelTexture(text: string, width = LABEL_PX): CanvasTexture | null {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = width / 4
  const context = canvas.getContext('2d')
  if (!context) return null
  context.fillStyle = 'rgb(31, 41, 55)'
  context.font = '600 28px sans-serif'
  context.textAlign = 'center'
  context.textBaseline = 'middle'
  context.fillText(text, canvas.width / 2, canvas.height / 2, canvas.width - 8)
  const texture = new CanvasTexture(canvas)
  texture.colorSpace = SRGBColorSpace
  return texture
}

/**
 * The 3D model of an asset, standing on the ground with its footprint centred on the parent's origin and
 * heading along local +X. The parent group places and turns it. Unknown model keys get a neutral box.
 */
export function AssetVisual({
  modelKey,
  label,
  size,
  positionMode,
  uncertaintyM,
  footprint,
  path,
  details,
}: AssetVisualProps) {
  const spec = getModelSpec(modelKey)
  const ringRadius = spec?.mobile && positionMode === 'PARKED' ? (uncertaintyM ?? 0) : 0

  return (
    <group name={`asset-visual-${modelKey}`}>
      {spec ? (
        <KnownModel
          modelKey={modelKey}
          params={{ size, footprint, path, details: details ?? {} }}
        />
      ) : (
        <UnknownModel label={label} size={size} />
      )}
      {ringRadius > 0 && <UncertaintyRing radius={ringRadius} />}
    </group>
  )
}

function KnownModel({ modelKey, params }: { modelKey: string; params: ModelParams }) {
  const spec = getModelSpec(modelKey)!
  const { size } = params
  // Parametric models are redrawn when anything they depend on changes; the others only rescale
  const dependsOn = spec.parametric ? JSON.stringify(params) : null
  const built = useMemo(
    () => buildModel(spec, spec.parametric ? params : undefined),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [spec, dependsOn],
  )
  useEffect(() => () => disposeModel(built.group), [built])
  const caption = spec.caption?.(params)
  const captionText = caption?.text
  const texture = useMemo(
    () => (captionText ? labelTexture(captionText, CAPTION_PX) : null),
    [captionText],
  )
  useEffect(() => () => texture?.dispose(), [texture])
  return (
    <>
      <primitive object={built.group} name="model" scale={modelScale(spec, size)} />
      {texture && caption && (
        <sprite name="caption" position={[0, caption.height, 0]} scale={CAPTION_SCALE}>
          <spriteMaterial map={texture} depthTest={false} transparent />
        </sprite>
      )}
    </>
  )
}

function UnknownModel({ label, size }: { label?: string; size: ModelSize }) {
  const texture = useMemo(() => (label ? labelTexture(label) : null), [label])
  useEffect(() => () => texture?.dispose(), [texture])
  return (
    <group name="unknown-model">
      <mesh
        name="model"
        position={[0, size.height / 2, 0]}
        scale={[size.length, size.height, size.width]}
        material={matteMaterial(COLOUR.NEUTRAL)}
      >
        <boxGeometry args={[1, 1, 1]} />
      </mesh>
      {texture && (
        <sprite name="label" position={[0, size.height + 2, 0]} scale={[16, 4, 1]}>
          <spriteMaterial map={texture} depthTest={false} transparent />
        </sprite>
      )}
    </group>
  )
}

/** Flat ring on the ground: where a parked machine may really be. */
function UncertaintyRing({ radius }: { radius: number }) {
  const width = Math.max(0.3, radius * 0.06)
  return (
    <mesh name="uncertainty-ring" rotation={[-Math.PI / 2, 0, 0]} position={[0, 0.05, 0]}>
      <ringGeometry args={[Math.max(0, radius - width), radius, 64]} />
      <meshBasicMaterial
        color={COLOUR.RING}
        transparent
        opacity={RING_OPACITY}
        depthWrite={false}
      />
    </mesh>
  )
}
