import {
  BoxGeometry,
  Group,
  InstancedMesh,
  Matrix4,
  Mesh,
  Quaternion,
  Vector3,
  type BufferGeometry,
  type Material,
} from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import { matteMaterial } from '../models/box-batch'
import { envelopeSize } from '../models/envelope'
import { buildLayoutMeshes, type LayoutAsset, type LayoutCaption } from '../models/layout-batch'
import { COLOUR } from '../models/model-spec'
import { buildModel, getModelSpec, modelScale } from '../models/registry'

/** Beyond this distance from the camera an asset is drawn as a plain coloured box. */
export const LOD_DISTANCE_M = 1500
/** Assets are grouped in square cells of this side; a cell switches between detailed and simple as a whole. */
export const LOD_CELL_M = 300
/** A model repeated at least this often, with the same size and details, is drawn instanced. */
export const INSTANCE_MIN = 3

export interface LodCell {
  /** Centre of the assets of the cell on the ground (scene x, z). */
  centre: [x: number, z: number]
  detailed: Group
  simple: Group
}

export interface LayoutLod {
  group: Group
  cells: LodCell[]
  captions: LayoutCaption[]
  /** Assets drawn through InstancedMesh, and the number of those meshes. */
  instancedAssets: number
  instancedMeshes: number
  /** Model keys the library does not know. */
  unknown: string[]
  /** Draw calls with every cell detailed, and with every cell simple. */
  drawCalls: { detailed: number; simple: number }
  dispose: () => void
}

/** One placed asset with the colour its plain box is drawn in (0xRRGGBB). */
export type LodAsset = LayoutAsset & { colour?: number }

/** Identical assets of one model: size and details decide whether they can share a drawing. */
function signature(asset: LayoutAsset): string | null {
  if (asset.footprint || asset.path) return null
  const { length, width, height } = asset.size
  return `${asset.modelKey}|${length}|${width}|${height}|${JSON.stringify(asset.details ?? {})}`
}

function placement(asset: LayoutAsset, scale: [number, number, number] = [1, 1, 1]): Matrix4 {
  return new Matrix4().compose(
    new Vector3(...asset.position),
    new Quaternion().setFromAxisAngle(new Vector3(0, 1, 0), asset.rotationY ?? 0),
    new Vector3(...scale),
  )
}

/** One InstancedMesh per material of the model, sharing one geometry for every copy. */
function instance(
  assets: readonly LayoutAsset[],
): { group: Group; geometries: BufferGeometry[] } | null {
  const first = assets[0]!
  const spec = getModelSpec(first.modelKey)
  if (!spec) return null
  const built = buildModel(
    spec,
    spec.parametric ? { size: first.size, details: first.details ?? {} } : undefined,
  )
  const matrices = assets.map((a) => placement(a, modelScale(spec, a.size)))
  const group = new Group()
  const geometries: BufferGeometry[] = []
  for (const mesh of built.group.children as Mesh[]) {
    const instanced = new InstancedMesh(mesh.geometry, mesh.material as Material, matrices.length)
    instanced.renderOrder = mesh.renderOrder
    matrices.forEach((m, i) => instanced.setMatrixAt(i, m))
    instanced.instanceMatrix.needsUpdate = true
    instanced.computeBoundingSphere()
    group.add(instanced)
    geometries.push(mesh.geometry)
  }
  return { group, geometries }
}

/** The plain boxes of a cell, merged per colour: one draw call per colour. */
function simpleBoxes(assets: readonly LodAsset[]): Group {
  const byColour = new Map<number, BufferGeometry[]>()
  for (const asset of assets) {
    const size = envelopeSize(getModelSpec(asset.modelKey), asset.size)
    // A drawn footprint or path is not always centred on the anchor
    const ring = asset.footprint ?? asset.path
    let cx = 0
    let cz = 0
    let { length, width } = size
    if (ring && ring.length > 0) {
      const xs = ring.map((p) => p[0])
      const zs = ring.map((p) => p[1])
      cx = (Math.min(...xs) + Math.max(...xs)) / 2
      cz = (Math.min(...zs) + Math.max(...zs)) / 2
      if (asset.footprint) {
        length = Math.max(...xs) - Math.min(...xs)
        width = Math.max(...zs) - Math.min(...zs)
      }
    }
    const box = new BoxGeometry(Math.max(length, 0.5), size.height, Math.max(width, 0.5))
    box.translate(cx, size.height / 2, cz)
    box.applyMatrix4(placement(asset))
    const colour = asset.colour ?? COLOUR.NEUTRAL
    byColour.set(colour, [...(byColour.get(colour) ?? []), box])
  }
  const group = new Group()
  for (const [colour, boxes] of byColour) {
    const merged = mergeGeometries(boxes, false)
    boxes.forEach((b) => b.dispose())
    const mesh = new Mesh(merged, matteMaterial(colour))
    mesh.name = 'lod-simple'
    group.add(mesh)
  }
  return group
}

/**
 * The layout as it is drawn: repeated models instanced, everything else merged per cell and material, and per
 * cell a plain-box version for when the camera is far. `updateLod` switches the cells by camera distance.
 */
export function buildLayoutLod(assets: readonly LodAsset[]): LayoutLod {
  const group = new Group()
  group.name = 'layout-meshes'
  const sharedGeometries: BufferGeometry[] = []
  const captions: LayoutCaption[] = []
  const unknown: string[] = []
  let instancedAssets = 0
  let instancedMeshes = 0

  const repeated = new Map<string, LodAsset[]>()
  for (const asset of assets) {
    const key = signature(asset)
    if (key && getModelSpec(asset.modelKey))
      repeated.set(key, [...(repeated.get(key) ?? []), asset])
  }
  const instanced = new Set<LodAsset>()
  for (const copies of repeated.values()) {
    if (copies.length < INSTANCE_MIN) continue
    const drawn = instance(copies)
    if (!drawn) continue
    group.add(drawn.group)
    sharedGeometries.push(...drawn.geometries)
    copies.forEach((c) => instanced.add(c))
    instancedAssets += copies.length
    instancedMeshes += drawn.group.children.length
    for (const a of copies) {
      const caption = getModelSpec(a.modelKey)?.caption?.({
        size: a.size,
        details: a.details ?? {},
      })
      if (caption) {
        const [x, y, z] = a.position
        captions.push({ text: caption.text, assetId: a.id, position: [x, y + caption.height, z] })
      }
    }
  }

  const cellsOf = new Map<string, LodAsset[]>()
  for (const asset of assets) {
    if (instanced.has(asset)) continue
    const key = `${Math.floor(asset.position[0] / LOD_CELL_M)}:${Math.floor(asset.position[2] / LOD_CELL_M)}`
    cellsOf.set(key, [...(cellsOf.get(key) ?? []), asset])
  }

  const cells: LodCell[] = []
  let detailedCalls = instancedMeshes
  let simpleCalls = instancedMeshes
  for (const members of cellsOf.values()) {
    const meshes = buildLayoutMeshes(members)
    captions.push(...meshes.captions)
    unknown.push(...meshes.unknown)
    const simple = simpleBoxes(members.filter((a) => getModelSpec(a.modelKey)))
    const detailed = meshes.group
    detailed.name = 'lod-detailed'
    simple.name = 'lod-simple-cell'
    simple.visible = false
    group.add(detailed, simple)
    const xs = members.map((a) => a.position[0])
    const zs = members.map((a) => a.position[2])
    cells.push({
      centre: [(Math.min(...xs) + Math.max(...xs)) / 2, (Math.min(...zs) + Math.max(...zs)) / 2],
      detailed,
      simple,
    })
    detailedCalls += detailed.children.length
    simpleCalls += simple.children.length
  }

  return {
    group,
    cells,
    captions,
    instancedAssets,
    instancedMeshes,
    unknown,
    drawCalls: { detailed: detailedCalls, simple: simpleCalls },
    dispose: () => {
      // Instanced meshes share the geometries listed here; every other mesh owns its own
      sharedGeometries.forEach((g) => g.dispose())
      group.traverse((o) => {
        if (o instanceof Mesh && !(o instanceof InstancedMesh)) o.geometry.dispose()
      })
    },
  }
}

/** Shows the detailed cells near the camera and the plain boxes beyond 1,500 m. True when anything changed. */
export function updateLod(lod: LayoutLod, camera: { x: number; y: number; z: number }): boolean {
  let changed = false
  for (const cell of lod.cells) {
    const far =
      Math.hypot(camera.x - cell.centre[0], camera.y, camera.z - cell.centre[1]) > LOD_DISTANCE_M
    if (cell.detailed.visible === far) {
      cell.detailed.visible = !far
      cell.simple.visible = far
      changed = true
    }
  }
  return changed
}
