import {
  patchOf,
  ringsOfShape,
  subjectOf,
  type EditShape,
  type EditSubject,
} from '@/features/design/edit/edit-model'
import { removeVertex } from '@/features/design/edit/edit-ops'
import { checkEdit, type Neighbour, type RuleContext } from '@/features/design/edit/edit-rules'
import { fromLonLat, type Local } from '@/lib/geo'
import type { AssetType, Port } from '@/lib/api'
import type { AssetFields } from './asset-fields'

/** The rules of the port and the other assets of the draft, the inputs every edit of the Inspector is checked with. */
export interface RuleInputs {
  port: Port
  types: ReadonlyMap<string, AssetType>
  assets: readonly AssetFields[]
}

/** The stored outline of a port ring as local metres, without the closing point. */
function openRing(coordinates: number[][][] | undefined): Local[] | null {
  const ring = coordinates?.[0]
  if (!ring || ring.length < 4) return null
  const points = ring.slice(0, -1).map((p) => fromLonLat(p[0]!, p[1]!))
  return points
}

/**
 * The context a move is checked in, for one asset: the same neighbours and extent the map uses. An asset whose shape the
 * editor cannot read is not a neighbour (the server still checks it).
 */
export function ruleContextOf(inputs: RuleInputs, self: AssetFields, type: AssetType): RuleContext {
  const neighbours: Neighbour[] = []
  for (const other of inputs.assets) {
    if (other.id === self.id) continue
    const subject = subjectOf(other, inputs.types.get(other.typeCode))
    if (!subject) continue
    const { rings, closed } = ringsOfShape(subject.shape)
    const zone = other.details?.zone?.allowedTypes
    neighbours.push({
      id: other.id,
      name: other.name,
      typeCode: other.typeCode,
      rings,
      closed,
      allowedTypes: Array.isArray(zone) ? zone : [],
    })
  }
  const land = inputs.port.landExtent ? openRing(inputs.port.landExtent.coordinates) : null
  return {
    type,
    extent: openRing(inputs.port.extent.coordinates),
    land,
    hasQuay: inputs.port.quayLine !== null,
    neighbours,
    types: inputs.types,
    overridden: (self.override?.reason ?? '').trim() !== '',
  }
}

/** A candidate change: the shape it gives, and the PATCH that stores it when the rules let it through. */
export type Proposal =
  { ok: true; shape: EditShape; changes: Record<string, unknown> } | { ok: false; reason: string }

/** Checks a candidate shape with the move rules and builds the PATCH that stores it. */
export function proposeShape(subject: EditSubject, shape: EditShape, ctx: RuleContext): Proposal {
  const verdict = checkEdit(subject, shape, ctx)
  if (!verdict.ok) return { ok: false, reason: verdict.reason }
  return { ok: true, shape, changes: patchOf(subject, shape) }
}

export function withSize(shape: EditShape, size: { length?: number; width?: number }): EditShape {
  return shape.kind === 'rect' ? { ...shape, ...size } : shape
}

export function withHeading(shape: EditShape, headingDeg: number): EditShape {
  return shape.kind === 'rect' ? { ...shape, headingDeg } : shape
}

export function withRadius(shape: EditShape, radius: number): EditShape {
  return shape.kind === 'circle' ? { ...shape, radius } : shape
}

export function withAnchor(shape: EditShape, anchor: Local): EditShape {
  return shape.kind === 'rect' || shape.kind === 'circle' ? { ...shape, anchor } : shape
}

/** The berth keeps its start and moves its end along its own direction, so the length is the one asked for. */
export function withBerthLength(shape: EditShape, length: number): EditShape {
  if (shape.kind !== 'berth') return shape
  const dx = shape.b[0] - shape.a[0]
  const dy = shape.b[1] - shape.a[1]
  const span = Math.hypot(dx, dy)
  if (span === 0) return shape
  const b: Local = [shape.a[0] + (dx / span) * length, shape.a[1] + (dy / span) * length]
  return { ...shape, b }
}

/** Slides a berth, a rail crane or a circle along the quay (local x) by the given metres. */
export function slideAlongQuay(shape: EditShape, dx: number): EditShape {
  switch (shape.kind) {
    case 'berth':
      return { ...shape, a: [shape.a[0] + dx, shape.a[1]], b: [shape.b[0] + dx, shape.b[1]] }
    case 'rect':
    case 'circle':
      return { ...shape, anchor: [shape.anchor[0] + dx, shape.anchor[1]] }
    default:
      return shape
  }
}

/** A vertex of an outline or a line moved to a new local point. */
export function withVertex(shape: EditShape, index: number, point: Local): EditShape {
  if (shape.kind !== 'polygon' && shape.kind !== 'line') return shape
  return { ...shape, points: shape.points.map((p, i) => (i === index ? point : p)) }
}

/** The shape without one vertex; null when the outline would fall below its minimum. */
export function withoutVertex(shape: EditShape, index: number): EditShape | null {
  return removeVertex(shape, index)
}

/** The local start of a berth: its chainage in metres along the quay. */
export const chainageOf = (shape: EditShape): number | null =>
  shape.kind === 'berth' ? shape.a[0] : null

/**
 * A storage pad changes its bays, rows or pitches: the footprint follows (bays x bay pitch by rows x row pitch). The pitch
 * that is not changed stays what it was, so the footprint keeps the pitch the editor sees.
 */
export function proposePad(
  subject: EditSubject,
  ctx: RuleContext,
  change: { bays?: number; rows?: number; bayPitchM?: number; rowPitchM?: number },
): Proposal {
  if (!subject.pad || subject.shape.kind !== 'rect')
    return { ok: false, reason: 'This pad has no footprint' }
  const shape = subject.shape
  const pad = subject.pad
  const bays = change.bays ?? pad.bays
  const rows = change.rows ?? pad.rows
  const bayPitch = change.bayPitchM ?? shape.length / pad.bays
  const rowPitch = change.rowPitchM ?? shape.width / pad.rows
  const next: EditShape = { ...shape, length: bays * bayPitch, width: rows * rowPitch }
  const proposal = proposeShape({ ...subject, pad: { bays, rows } }, next, ctx)
  if (!proposal.ok) return proposal
  const details = (proposal.changes.details ?? subject.asset.details ?? {}) as Record<
    string,
    Record<string, unknown>
  >
  return {
    ok: true,
    shape: next,
    changes: {
      ...proposal.changes,
      details: { ...details, storagePad: { ...details.storagePad, bays, rows } },
    },
  }
}

/** The local bounding box of a set of rings, in metres: the envelope of an outline. */
export function localBounds(rings: Local[][]): { width: number; height: number } {
  const points = rings.flat()
  if (points.length === 0) return { width: 0, height: 0 }
  const xs = points.map((p) => p[0])
  const ys = points.map((p) => p[1])
  return { width: Math.max(...xs) - Math.min(...xs), height: Math.max(...ys) - Math.min(...ys) }
}
