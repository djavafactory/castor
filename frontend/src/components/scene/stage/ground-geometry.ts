import { BufferAttribute, BufferGeometry, ShapeUtils, Vector2 } from 'three'
import type { Local } from '@/lib/geo'
import type { Cell } from './tiles'

/** Sutherland-Hodgman clip of a polygon to the rectangle of one imagery cell. */
export function clipToRect(
  ring: readonly Local[],
  r: { x0: number; x1: number; y0: number; y1: number },
): Local[] {
  type Edge = { inside: (p: Local) => boolean; cut: (a: Local, b: Local) => Local }
  const atX =
    (x: number) =>
    (a: Local, b: Local): Local => [x, a[1] + ((b[1] - a[1]) * (x - a[0])) / (b[0] - a[0])]
  const atY =
    (y: number) =>
    (a: Local, b: Local): Local => [a[0] + ((b[0] - a[0]) * (y - a[1])) / (b[1] - a[1]), y]
  const edges: Edge[] = [
    { inside: (p) => p[0] >= r.x0, cut: atX(r.x0) },
    { inside: (p) => p[0] <= r.x1, cut: atX(r.x1) },
    { inside: (p) => p[1] >= r.y0, cut: atY(r.y0) },
    { inside: (p) => p[1] <= r.y1, cut: atY(r.y1) },
  ]
  let out: Local[] = [...ring]
  for (const edge of edges) {
    const input = out
    out = []
    for (let i = 0; i < input.length; i++) {
      const a = input[i]
      const b = input[(i + 1) % input.length]
      if (edge.inside(b)) {
        if (!edge.inside(a)) out.push(edge.cut(a, b))
        out.push(b)
      } else if (edge.inside(a)) {
        out.push(edge.cut(a, b))
      }
    }
    if (out.length === 0) break
  }
  return out
}

/**
 * The part of a ground polygon that lies in one imagery cell, flat at height y, with UVs into the cell's canvas.
 * Returns null when the polygon does not reach the cell.
 */
export function cellGeometry(
  polygon: readonly Local[],
  cell: Cell,
  y: number,
): BufferGeometry | null {
  const ring = clipToRect(polygon, cell)
  if (ring.length < 3) return null
  const faces = ShapeUtils.triangulateShape(
    ring.map((p) => new Vector2(p[0], p[1])),
    [],
  )
  if (faces.length === 0) return null
  const positions = new Float32Array(ring.length * 3)
  const normals = new Float32Array(ring.length * 3)
  const uvs = new Float32Array(ring.length * 2)
  ring.forEach(([x, ly], i) => {
    positions.set([x, y, -ly], i * 3)
    normals.set([0, 1, 0], i * 3)
    uvs.set([(x - cell.x0) / (cell.x1 - cell.x0), (ly - cell.y0) / (cell.y1 - cell.y0)], i * 2)
  })
  const index: number[] = []
  for (const [a, b, c] of faces) {
    const cross =
      (ring[b][0] - ring[a][0]) * (ring[c][1] - ring[a][1]) -
      (ring[b][1] - ring[a][1]) * (ring[c][0] - ring[a][0])
    if (cross >= 0) index.push(a, b, c)
    else index.push(a, c, b)
  }
  const g = new BufferGeometry()
  g.setAttribute('position', new BufferAttribute(positions, 3))
  g.setAttribute('normal', new BufferAttribute(normals, 3))
  g.setAttribute('uv', new BufferAttribute(uvs, 2))
  g.setIndex(index)
  g.computeBoundingSphere()
  return g
}
