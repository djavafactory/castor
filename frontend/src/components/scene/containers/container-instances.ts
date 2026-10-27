import { BoxGeometry, Color, Group, InstancedMesh, Matrix4, Mesh } from 'three'
import { matteMaterial } from '../models/box-batch'
import {
  CONTAINER_COLOURS,
  PAD_COLOUR,
  PAD_TOP,
  drawnSize,
  generateBoxes,
  teuOf,
  type BoxSize,
  type ContainerBox,
  type YardSpec,
} from './container-stacks'

export interface BuiltYard {
  /** The pad and one InstancedMesh per box size, around the yard centre. */
  group: Group
  boxes: ContainerBox[]
  teu: number
}

const unitBox = new BoxGeometry(1, 1, 1)
const white = () => matteMaterial(0xffffff)

function instancesOf(size: BoxSize, boxes: ContainerBox[]): InstancedMesh {
  const mesh = new InstancedMesh(unitBox, white(), boxes.length)
  mesh.name = `containers-${size}`
  const [l, h, w] = drawnSize(size)
  const matrix = new Matrix4()
  const colour = new Color()
  boxes.forEach((box, i) => {
    matrix.makeScale(l, h, w).setPosition(box.x, box.y, box.z)
    mesh.setMatrixAt(i, matrix)
    colour.setHex(CONTAINER_COLOURS[box.colourKey]).multiplyScalar(box.brightness)
    colour.r = Math.min(1, colour.r)
    colour.g = Math.min(1, colour.g)
    colour.b = Math.min(1, colour.b)
    mesh.setColorAt(i, colour)
  })
  mesh.instanceMatrix.needsUpdate = true
  if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true
  // Frustum culling stays on: it needs the bounds of the instances
  mesh.frustumCulled = true
  mesh.computeBoundingSphere()
  mesh.computeBoundingBox()
  return mesh
}

/** Draws a yard: the pad, and its boxes as one InstancedMesh per box size. Free it with `disposeYard`. */
export function buildYard(yard: YardSpec): BuiltYard {
  const boxes = generateBoxes(yard)
  const group = new Group()
  group.name = `container-yard-${yard.code}`
  const pad = new Mesh(new BoxGeometry(yard.length, PAD_TOP, yard.width), matteMaterial(PAD_COLOUR))
  pad.name = 'pad'
  pad.position.y = PAD_TOP / 2
  group.add(pad)
  for (const size of [20, 40] as const) {
    const ofSize = boxes.filter((box) => box.size === size)
    if (ofSize.length > 0) group.add(instancesOf(size, ofSize))
  }
  return { group, boxes, teu: teuOf(boxes) }
}

/** Frees what the yard owns: its pad geometry and instance buffers. The shared box geometry stays. */
export function disposeYard(group: Group) {
  for (const child of group.children) {
    if (child instanceof InstancedMesh) child.dispose()
    else if (child instanceof Mesh) child.geometry.dispose()
  }
}
