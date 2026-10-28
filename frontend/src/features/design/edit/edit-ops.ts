import { headingToRotation, rotationToHeading, type Local } from '@/lib/geo'
import { localToScreen, type MapSize, type MapView } from '@/components/map/map-view'
import { MIN_BERTH_M, round2, type EditShape, type EditSubject } from './edit-model'

/** Pixels: a handle is a 10 px square, the rotation knob a 6 px circle 28 px beyond the front edge. */
export const HANDLE_PX = 10
export const KNOB_RADIUS_PX = 6
export const KNOB_OFFSET_PX = 28
/** A pointer within this many pixels of a handle's centre grabs it. */
export const HANDLE_HIT_PX = 7
/** A press that moves less than this is a click, not a move. */
export const MOVE_THRESHOLD_PX = 3
/** Shift snaps the heading to multiples of this. */
export const SNAP_DEG = 15

export type HandleKind = 'corner' | 'rotate' | 'radius' | 'end' | 'vertex' | 'mid'

export interface Handle {
  kind: HandleKind
  /** Corner 0..3, berth end 0..1, vertex index, or the edge a midpoint sits on. */
  index: number
  at: Local
  /** The rotation knob hangs off this point of the shape; its stem is drawn from here. */
  stemFrom?: Local
}

const RAD = Math.PI / 180

/** Own-frame signs of the four corners, in the order of `rectangleLocal`. */
const SIGN_X = [-1, 1, 1, -1]
const SIGN_Y = [-1, -1, 1, 1]

function axes(headingDeg: number) {
  const a = headingToRotation(headingDeg) * RAD
  return { c: Math.cos(a), s: Math.sin(a) }
}

/** Own frame (along the length, across the width) to local metres, relative to the anchor. */
const toLocalOffset = (c: number, s: number, x: number, y: number): Local => [
  x * c - y * s,
  x * s + y * c,
]

export function editableHandles(subject: EditSubject, k: number): Handle[] {
  const shape = subject.shape
  switch (shape.kind) {
    case 'rect': {
      const { c, s } = axes(shape.headingDeg)
      const corners: Handle[] = SIGN_X.map((sx, i) => {
        const [dx, dy] = toLocalOffset(
          c,
          s,
          (sx * shape.length) / 2,
          (SIGN_Y[i]! * shape.width) / 2,
        )
        return { kind: 'corner', index: i, at: [shape.anchor[0] + dx, shape.anchor[1] + dy] }
      })
      // A rail crane keeps the heading of its rail
      if (subject.alongQuay) return corners
      const [fx, fy] = toLocalOffset(c, s, shape.length / 2, 0)
      const [kx, ky] = toLocalOffset(c, s, shape.length / 2 + KNOB_OFFSET_PX / k, 0)
      return [
        ...corners,
        {
          kind: 'rotate',
          index: 0,
          at: [shape.anchor[0] + kx, shape.anchor[1] + ky],
          stemFrom: [shape.anchor[0] + fx, shape.anchor[1] + fy],
        },
      ]
    }
    case 'circle':
      return [{ kind: 'radius', index: 0, at: [shape.anchor[0] + shape.radius, shape.anchor[1]] }]
    case 'berth':
      return [
        { kind: 'end', index: 0, at: shape.a },
        { kind: 'end', index: 1, at: shape.b },
      ]
    case 'polygon':
    case 'line': {
      const points = shape.points
      const vertices: Handle[] = points.map((at, index) => ({ kind: 'vertex', index, at }))
      const edges = shape.kind === 'polygon' ? points.length : points.length - 1
      const mids: Handle[] = []
      for (let i = 0; i < edges; i++) {
        const p = points[i]!
        const q = points[(i + 1) % points.length]!
        mids.push({ kind: 'mid', index: i, at: [(p[0] + q[0]) / 2, (p[1] + q[1]) / 2] })
      }
      return [...vertices, ...mids]
    }
  }
}

/** The handle under a pixel: vertices and corners win over the midpoints beside them. */
export function handleAt(
  handles: readonly Handle[],
  view: MapView,
  size: MapSize,
  px: number,
  py: number,
): Handle | null {
  let best: Handle | null = null
  let bestDistance = Infinity
  for (const handle of handles) {
    const [hx, hy] = localToScreen(view, size, handle.at[0], handle.at[1])
    const distance = Math.hypot(px - hx, py - hy)
    // A midpoint only counts when no other handle is in reach
    const reach = handle.kind === 'mid' ? HANDLE_HIT_PX - 1 : HANDLE_HIT_PX
    const score = distance + (handle.kind === 'mid' ? 100 : 0)
    if (distance <= reach && score < bestDistance) {
      best = handle
      bestDistance = score
    }
  }
  return best
}

function translate(points: readonly Local[], dx: number, dy: number): Local[] {
  return points.map(([x, y]) => [x + dx, y + dy])
}

/** Moves the whole shape by a distance rounded to 0.01 m; berths and rail cranes only slide along the quay. */
export function moveShape(
  subject: EditSubject,
  shape: EditShape,
  dx: number,
  dy: number,
): EditShape {
  const mx = round2(dx)
  const my = subject.alongQuay ? 0 : round2(dy)
  switch (shape.kind) {
    case 'rect':
    case 'circle':
      return { ...shape, anchor: [shape.anchor[0] + mx, shape.anchor[1] + my] }
    case 'berth': {
      const [a, b] = translate([shape.a, shape.b], mx, my)
      return { ...shape, a: a!, b: b! }
    }
    case 'polygon':
    case 'line':
      return { ...shape, points: translate(shape.points, mx, my) }
  }
}

/**
 * Drags corner `corner` of a rectangle: the opposite corner stays, length and width follow the pointer in the asset's own
 * frame. A side never gets shorter than the type's minimum. A storage pad changes its bay and row pitch, so its size
 * stays bays x pitch by rows x pitch.
 */
export function resizeRect(subject: EditSubject, corner: number, pointer: Local): EditShape {
  const shape = subject.shape
  if (shape.kind !== 'rect') return shape
  const { c, s } = axes(shape.headingDeg)
  const sx = SIGN_X[corner]!
  const sy = SIGN_Y[corner]!
  const [fx, fy] = toLocalOffset(c, s, (-sx * shape.length) / 2, (-sy * shape.width) / 2)
  const fixed: Local = [shape.anchor[0] + fx, shape.anchor[1] + fy]
  // The pointer in the asset's own frame, from the fixed corner
  const px = pointer[0] - fixed[0]
  const py = pointer[1] - fixed[1]
  const ownX = px * c + py * s
  const ownY = -px * s + py * c
  const { limits, pad } = subject
  let length = round2(Math.max(limits.minLength, sx * ownX))
  let width = round2(Math.max(limits.minWidth, sy * ownY))
  if (pad) {
    // bays x pitch, in whole centimetres of pitch
    length = Math.round(pad.bays * Math.max(0.01, round2(length / pad.bays)) * 1e4) / 1e4
    width = Math.round(pad.rows * Math.max(0.01, round2(width / pad.rows)) * 1e4) / 1e4
  }
  const [cx, cy] = toLocalOffset(c, s, (sx * length) / 2, (sy * width) / 2)
  return { ...shape, length, width, anchor: [fixed[0] + cx, fixed[1] + cy] }
}

/** The true heading that points the front edge of a rectangle at the pointer, whole degrees; `snap` rounds to 15. */
export function rotateRect(shape: EditShape, pointer: Local, snap: boolean): EditShape {
  if (shape.kind !== 'rect') return shape
  const rotation = Math.atan2(pointer[1] - shape.anchor[1], pointer[0] - shape.anchor[0]) / RAD
  const heading = rotationToHeading(rotation)
  const step = snap ? SNAP_DEG : 1
  const rounded = Math.round(heading / step) * step
  return { ...shape, headingDeg: ((rounded % 360) + 360) % 360 }
}

/** Sets the radius of a circle from the pointer; never below the type's minimum (1 m at least). */
export function resizeCircle(subject: EditSubject, pointer: Local): EditShape {
  const shape = subject.shape
  if (shape.kind !== 'circle') return shape
  const distance = Math.hypot(pointer[0] - shape.anchor[0], pointer[1] - shape.anchor[1])
  return { ...shape, radius: round2(Math.max(subject.limits.minRadius, distance)) }
}

/** Drags one end of a berth along the quay line; the other end stays and the berth never gets shorter than 20 m. */
export function moveBerthEnd(subject: EditSubject, end: number, pointer: Local): EditShape {
  const shape = subject.shape
  if (shape.kind !== 'berth') return shape
  const moving = end === 0 ? shape.a : shape.b
  const fixed = end === 0 ? shape.b : shape.a
  const direction = moving[0] >= fixed[0] ? 1 : -1
  const min = Math.max(MIN_BERTH_M, subject.limits.minLength)
  const reach = Math.max(min, direction * (round2(pointer[0]) - fixed[0]))
  const next: Local = [fixed[0] + direction * reach, moving[1]]
  return end === 0 ? { ...shape, a: next } : { ...shape, b: next }
}

type Poly = Extract<EditShape, { kind: 'polygon' | 'line' }>

export function moveVertex(shape: EditShape, index: number, pointer: Local): EditShape {
  if (shape.kind !== 'polygon' && shape.kind !== 'line') return shape
  const points = shape.points.map((p, i): Local =>
    i === index ? [round2(pointer[0]), round2(pointer[1])] : p,
  )
  return { ...shape, points } as Poly
}

/** Puts a new vertex in the middle of edge `edge`; its index is edge + 1. */
export function insertVertex(shape: EditShape, edge: number): EditShape {
  if (shape.kind !== 'polygon' && shape.kind !== 'line') return shape
  const p = shape.points[edge]!
  const q = shape.points[(edge + 1) % shape.points.length]!
  const points = [...shape.points]
  points.splice(edge + 1, 0, [round2((p[0] + q[0]) / 2), round2((p[1] + q[1]) / 2)])
  return { ...shape, points } as Poly
}

/** Fewest points a polygon or line keeps. */
export const minPoints = (shape: EditShape) => (shape.kind === 'polygon' ? 3 : 2)

/** The shape without vertex `index`, or null when too few points would be left. */
export function removeVertex(shape: EditShape, index: number): EditShape | null {
  if (shape.kind !== 'polygon' && shape.kind !== 'line') return null
  if (shape.points.length <= minPoints(shape)) return null
  return { ...shape, points: shape.points.filter((_, i) => i !== index) } as Poly
}
