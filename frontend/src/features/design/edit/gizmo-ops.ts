import { headingToRotation, rotationToHeading, type Local } from '@/lib/geo'
import type { AssetType } from '@/lib/api'
import { formatMessage } from '@/lib/validation/rules'
import { MIN_BERTH_M, round2, type EditShape, type EditSubject } from './edit-model'
import { SNAP_DEG } from './edit-ops'

/** What a part of the 3D gizmo changes. */
export type GizmoPart = 'length' | 'width' | 'height' | 'rotate'
export type GizmoGesture = GizmoPart | 'move'

/** A 3D height never goes below this, metres; a storage pad's max height not below the server's 1 m. */
export const MIN_HEIGHT_M = 0.5
export const MIN_PAD_HEIGHT_M = 1
/** A berth is a line: its selection box is drawn this wide. */
const BERTH_BOX_WIDTH_M = 2

const RAD = Math.PI / 180

/** The box of a shape in its own frame: centre, rotation (degrees, counter-clockwise from local x) and its two sides. */
export interface ShapeBox {
  centre: Local
  rotationDeg: number
  length: number
  width: number
}

const num = (value: unknown): number | null =>
  typeof value === 'number' && Number.isFinite(value) ? value : null

/** The rotation of the own axes of a polygon or line: its stored heading, or the quay frame when it has none. */
function polyRotation(subject: EditSubject): number {
  const heading = num(subject.asset.headingDeg)
  return heading === null ? 0 : headingToRotation(heading)
}

const unit = (rotationDeg: number): Local => [
  Math.cos(rotationDeg * RAD),
  Math.sin(rotationDeg * RAD),
]

/** A point in the own frame (u along the length, v across) around `origin`. */
function own(p: Local, origin: Local, rotationDeg: number): Local {
  const [c, s] = unit(rotationDeg)
  const dx = p[0] - origin[0]
  const dy = p[1] - origin[1]
  return [dx * c + dy * s, -dx * s + dy * c]
}

function fromOwn(u: number, v: number, origin: Local, rotationDeg: number): Local {
  const [c, s] = unit(rotationDeg)
  return [origin[0] + u * c - v * s, origin[1] + u * s + v * c]
}

function extents(points: readonly Local[], rotationDeg: number) {
  const o = points.map((p) => own(p, [0, 0], rotationDeg))
  const us = o.map((p) => p[0])
  const vs = o.map((p) => p[1])
  return {
    u0: Math.min(...us),
    u1: Math.max(...us),
    v0: Math.min(...vs),
    v1: Math.max(...vs),
  }
}

/** The selection box of a shape: a rectangle is itself, a polygon or line its extent along its own heading axes. */
export function shapeBox(subject: EditSubject, shape: EditShape): ShapeBox {
  switch (shape.kind) {
    case 'rect':
      return {
        centre: shape.anchor,
        rotationDeg: headingToRotation(shape.headingDeg),
        length: shape.length,
        width: shape.width,
      }
    case 'circle':
      return {
        centre: shape.anchor,
        rotationDeg: 0,
        length: shape.radius * 2,
        width: shape.radius * 2,
      }
    case 'berth':
      return {
        centre: [(shape.a[0] + shape.b[0]) / 2, (shape.a[1] + shape.b[1]) / 2],
        rotationDeg: Math.atan2(shape.b[1] - shape.a[1], shape.b[0] - shape.a[0]) / RAD,
        length: Math.hypot(shape.b[0] - shape.a[0], shape.b[1] - shape.a[1]),
        width: BERTH_BOX_WIDTH_M,
      }
    case 'polygon':
    case 'line': {
      const rotationDeg = polyRotation(subject)
      const e = extents(shape.points, rotationDeg)
      return {
        centre: fromOwn((e.u0 + e.u1) / 2, (e.v0 + e.v1) / 2, [0, 0], rotationDeg),
        rotationDeg,
        length: e.u1 - e.u0,
        width: e.v1 - e.v0,
      }
    }
  }
}

/** Extents below this have nothing to scale: a straight line gets no arrow across it. */
const FLAT_M = 0.01

/** The parts of the gizmo an asset offers: berths only length; circles one size; a heading only where the type has one. */
export function gizmoParts(subject: EditSubject): GizmoPart[] {
  const shape = subject.shape
  switch (shape.kind) {
    case 'rect':
      return subject.alongQuay
        ? ['length', 'width', 'height']
        : ['length', 'width', 'height', 'rotate']
    case 'circle':
      return ['length', 'height']
    case 'berth':
      return ['length']
    case 'polygon':
    case 'line': {
      const box = shapeBox(subject, shape)
      return [
        ...(box.length >= FLAT_M ? (['length'] as const) : []),
        ...(box.width >= FLAT_M ? (['width'] as const) : []),
        'height',
      ]
    }
  }
}

/** Berths and rail cranes are not moved by pressing them in 3D: the camera orbits instead. */
export const movesOnPress = (subject: EditSubject) => !subject.alongQuay

/**
 * Drags the length or the width by `delta` metres along the positive own axis: the opposite edge stays. A side never
 * gets shorter than the type allows (2 m by default); a storage pad keeps bays x pitch; a circle changes its radius
 * (1 m at least); a polygon or line scales along its own heading axis; a berth only gets longer or shorter (20 m at
 * least).
 */
export function resizeAlong(
  subject: EditSubject,
  axis: 'length' | 'width',
  delta: number,
): EditShape {
  const shape = subject.shape
  const { limits, pad } = subject
  switch (shape.kind) {
    case 'rect': {
      const before = axis === 'length' ? shape.length : shape.width
      let after = round2(
        Math.max(axis === 'length' ? limits.minLength : limits.minWidth, before + delta),
      )
      const count = pad && (axis === 'length' ? pad.bays : pad.rows)
      // bays x pitch, in whole centimetres of pitch
      if (count) after = Math.round(count * Math.max(0.01, round2(after / count)) * 1e4) / 1e4
      const rotation = headingToRotation(shape.headingDeg) + (axis === 'width' ? 90 : 0)
      const [c, s] = unit(rotation)
      const shift = (after - before) / 2
      return {
        ...shape,
        [axis]: after,
        anchor: [shape.anchor[0] + c * shift, shape.anchor[1] + s * shift],
      }
    }
    case 'circle':
      return { ...shape, radius: round2(Math.max(limits.minRadius, shape.radius + delta)) }
    case 'berth': {
      if (axis !== 'length') return shape
      const before = Math.hypot(shape.b[0] - shape.a[0], shape.b[1] - shape.a[1])
      const after = Math.max(Math.max(MIN_BERTH_M, limits.minLength), round2(before + delta))
      const [c, s] = unit(Math.atan2(shape.b[1] - shape.a[1], shape.b[0] - shape.a[0]) / RAD)
      return { ...shape, b: [shape.a[0] + c * after, shape.a[1] + s * after] }
    }
    case 'polygon':
    case 'line': {
      const rotation = polyRotation(subject)
      const e = extents(shape.points, rotation)
      const [lo, hi] = axis === 'length' ? [e.u0, e.u1] : [e.v0, e.v1]
      const before = hi - lo
      if (before < FLAT_M) return shape
      const min = axis === 'length' ? limits.minLength : limits.minWidth
      const f = round2(Math.max(min, before + delta)) / before
      const points = shape.points.map((p): Local => {
        const [u, v] = own(p, [0, 0], rotation)
        const [x, y] =
          axis === 'length'
            ? fromOwn(lo + (u - lo) * f, v, [0, 0], rotation)
            : fromOwn(u, lo + (v - lo) * f, [0, 0], rotation)
        // A point on the edge that stays keeps its stored value: it moves less than a stored coordinate can tell (1 cm)
        return Math.hypot(x - p[0], y - p[1]) < 0.01 ? p : [round2(x), round2(y)]
      })
      return { ...shape, points }
    }
  }
}

/** The height the gizmo edits: `heightM`, or a storage pad's max height. */
export interface HeightSpec {
  value: number
  min: number
  max: number | null
  pad: boolean
}

export function heightOf(subject: EditSubject, type: AssetType): HeightSpec {
  if (subject.pad) {
    const details = subject.asset.details as { storagePad?: { maxHeightM?: unknown } } | undefined
    return {
      value: num(details?.storagePad?.maxHeightM) ?? type.defaults.heightM ?? MIN_PAD_HEIGHT_M,
      min: MIN_PAD_HEIGHT_M,
      max: null,
      pad: true,
    }
  }
  const min = Math.max(MIN_HEIGHT_M, type.min.heightM ?? 0)
  return {
    value: num(subject.asset.heightM) ?? type.defaults.heightM ?? min,
    min,
    max: type.max.heightM,
    pad: false,
  }
}

/** Drags the height by `delta` metres, in whole centimetres, never below the minimum. */
export const resizeHeight = (spec: HeightSpec, delta: number) =>
  round2(Math.max(spec.min, spec.value + delta))

/** Why a height is refused: above the type's maximum. */
export function heightProblem(spec: HeightSpec, value: number): string | null {
  const label = spec.pad ? 'Max height' : 'Height'
  if (value < spec.min) return formatMessage('DIM_OUT_OF_RANGE', 'min', { label, limit: spec.min })
  if (spec.max !== null && value > spec.max) {
    return formatMessage('DIM_OUT_OF_RANGE', 'max', { label, limit: spec.max })
  }
  return null
}

/** The PATCH body of a new height. */
export function heightPatch(subject: EditSubject, spec: HeightSpec, value: number) {
  if (!spec.pad) return { heightM: value }
  const details = (subject.asset.details ?? {}) as Record<string, unknown>
  return {
    details: {
      ...details,
      storagePad: { ...(details.storagePad as Record<string, unknown>), maxHeightM: value },
    },
  }
}

/**
 * Turns a rectangle by the angle the pointer swept around its anchor (degrees, counter-clockwise), from its stored
 * heading: whole degrees, or multiples of 15 with `snap`.
 */
export function rotateBy(shape: EditShape, sweptDeg: number, snap: boolean): EditShape {
  if (shape.kind !== 'rect') return shape
  const heading = rotationToHeading(headingToRotation(shape.headingDeg) + sweptDeg)
  const step = snap ? SNAP_DEG : 1
  const rounded = Math.round(heading / step) * step
  return { ...shape, headingDeg: ((rounded % 360) + 360) % 360 }
}

/** The history line of a gizmo edit. */
export function gizmoLabel(gesture: GizmoGesture, name: string): string {
  switch (gesture) {
    case 'move':
      return `Moved ${name}`
    case 'rotate':
      return `Rotated ${name}`
    default:
      return `Changed the ${gesture} of ${name}`
  }
}
