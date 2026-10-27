import { useFrame, useThree } from '@react-three/fiber'
import { useEffect, useMemo, useRef } from 'react'
import { CanvasTexture, SRGBColorSpace, type Sprite } from 'three'
import { palette } from '@/features/scene/palette'

/** Labels are hidden beyond this distance from the camera, metres, per layer. */
export const LABEL_RANGE: Record<string, number> = {
  quay: 2500,
  berth: 1800,
  asset: 900,
  building: 700,
}

const PX_HEIGHT = 24
const FONT = '600 22px "Be Vietnam Pro", system-ui, sans-serif'

export interface PillStyle {
  text: string
  /** Dot colour (a palette string). */
  dot: string
  /** Hover: white text on signal. */
  hover?: boolean
}

/** Draws the pill: paper fill, ink text, a thin border and a coloured dot. Null where there is no canvas. */
export function drawPill(style: PillStyle): { canvas: HTMLCanvasElement; aspect: number } | null {
  if (typeof document === 'undefined') return null
  const canvas = document.createElement('canvas')
  const ctx = canvas.getContext('2d')
  if (!ctx) return null
  ctx.font = FONT
  const textW = Math.ceil(ctx.measureText(style.text).width)
  const h = 36
  const pad = 14
  const dot = style.hover ? 0 : 20
  const w = textW + pad * 2 + dot
  canvas.width = w
  canvas.height = h
  ctx.font = FONT
  ctx.textBaseline = 'middle'
  ctx.beginPath()
  ctx.roundRect(1, 1, w - 2, h - 2, h / 2)
  ctx.fillStyle = style.hover ? palette.signal : palette.label.fill
  ctx.fill()
  ctx.lineWidth = 2
  ctx.strokeStyle = style.hover ? palette.signal : palette.label.border
  ctx.stroke()
  if (!style.hover) {
    ctx.beginPath()
    ctx.arc(pad + 5, h / 2, 5, 0, Math.PI * 2)
    ctx.fillStyle = style.dot
    ctx.fill()
  }
  ctx.fillStyle = style.hover ? palette.white : palette.ink
  ctx.fillText(style.text, pad + dot, h / 2 + 1)
  return { canvas, aspect: w / h }
}

/** Sprite scale that makes a sprite `px` pixels tall on screen at any distance (sizeAttenuation off). */
export function screenScale(px: number, viewportHeight: number, fovDeg: number): number {
  return (px / viewportHeight) * 2 * Math.tan((fovDeg * Math.PI) / 360)
}

/** A pill label that keeps its screen size and is hidden beyond the range of its layer. */
export function Label({
  position,
  text,
  dot = palette.signal,
  layer = 'asset',
  hover = false,
}: {
  position: [number, number, number]
  text: string
  dot?: string
  layer?: keyof typeof LABEL_RANGE
  hover?: boolean
}) {
  const sprite = useRef<Sprite>(null)
  const size = useThree((s) => s.size)
  const camera = useThree((s) => s.camera)
  const pill = useMemo(() => drawPill({ text, dot, hover }), [text, dot, hover])
  const texture = useMemo(() => {
    if (!pill) return null
    const t = new CanvasTexture(pill.canvas)
    t.colorSpace = SRGBColorSpace
    return t
  }, [pill])
  useEffect(() => () => texture?.dispose(), [texture])
  const range = LABEL_RANGE[layer] ?? LABEL_RANGE.asset

  useFrame(() => {
    const s = sprite.current
    if (!s || !pill) return
    const fov = 'fov' in camera ? (camera.fov as number) : 30
    const h = screenScale(PX_HEIGHT, size.height, fov)
    s.scale.set(h * pill.aspect, h, 1)
    s.visible = camera.position.distanceTo(s.position) < range
  })

  if (!texture) return null
  return (
    <sprite ref={sprite} position={position} renderOrder={10} name={`label:${text}`}>
      <spriteMaterial
        map={texture}
        sizeAttenuation={false}
        depthTest={false}
        transparent
        toneMapped={false}
      />
    </sprite>
  )
}
