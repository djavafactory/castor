import { useThree } from '@react-three/fiber'
import { useEffect } from 'react'
import { Box3, BufferGeometry, InstancedMesh, Mesh, Vector3, type Object3D } from 'three'
import { testHooksEnabled } from '@/lib/test-hooks'
import { LAYERS, type FlatLayerKey } from './layer-heights'
import { view } from './view-state'

/** What the end-to-end tests (JOIN-04) can read from the live 3D scene. Numbers and positions only. */
export interface SceneStats {
  /** Draw calls of the last frame. */
  calls: number
  triangles: number
  geometries: number
  textures: number
  /** Instances of all instanced meshes (the containers). */
  instances: number
}

export interface AssetBox {
  /** The model key, from the group name asset-visual-<KEY>. */
  key: string
  min: [number, number, number]
  max: [number, number, number]
  /** Distinct [x, z] of the vertices at the top of the asset (within a millimetre of its highest point). */
  top: [number, number][]
}

/** The layout geometry round a point: its extent in height and the corners at its top. */
export interface NearGeometry {
  vertices: number
  minY: number
  maxY: number
  /** Extent in the ground plane: [minX, maxX, minZ, maxZ]. */
  extent: [number, number, number, number]
  /** Distinct [x, z] of the vertices within a millimetre of the highest one. */
  top: [number, number][]
}

export interface FlatCheck {
  layer: string
  expectedY: number
  vertices: number
  /** Largest distance of a vertex from the layer height, metres. */
  worst: number
}

export interface SceneProbe {
  stats(): SceneStats
  assets(): AssetBox[]
  flat(): FlatCheck[]
  /** The vertices of the drawn layout (not its picking, hover and selection helpers) within `radius` of scene [x, z]. */
  near(x: number, z: number, radius: number): NearGeometry
  /** Turns the camera round its target to this azimuth (degrees from the current one), keeping distance and tilt. */
  orbit(deltaDeg: number): void
  /** Renders one frame now and returns its stats. */
  render(): SceneStats
}

const round = (n: number) => Math.round(n * 1000) / 1000

function walk(root: Object3D, visit: (o: Object3D) => void) {
  root.traverse((o) => {
    if (o.visible) visit(o)
  })
}

function worldVertices(mesh: Mesh): Vector3[] {
  const geometry = mesh.geometry as BufferGeometry
  const position = geometry.getAttribute('position')
  if (!position) return []
  mesh.updateWorldMatrix(true, false)
  const out: Vector3[] = []
  for (let i = 0; i < position.count; i++) {
    out.push(new Vector3().fromBufferAttribute(position, i).applyMatrix4(mesh.matrixWorld))
  }
  return out
}

export function SceneProbeBridge() {
  const gl = useThree((s) => s.gl)
  const scene = useThree((s) => s.scene)
  const camera = useThree((s) => s.camera)
  const invalidate = useThree((s) => s.invalidate)

  useEffect(() => {
    if (!testHooksEnabled()) return
    const hooks = globalThis as { __castorScene?: SceneProbe }
    const stats = (): SceneStats => {
      let instances = 0
      walk(scene, (o) => {
        if (o instanceof InstancedMesh) instances += o.count
      })
      const { render, memory } = gl.info
      return {
        calls: render.calls,
        triangles: render.triangles,
        geometries: memory.geometries,
        textures: memory.textures,
        instances,
      }
    }
    hooks.__castorScene = {
      stats,
      assets() {
        const found: AssetBox[] = []
        walk(scene, (o) => {
          if (!o.name.startsWith('asset-visual-')) return
          const box = new Box3().setFromObject(o)
          const top = new Map<string, [number, number]>()
          walk(o, (m) => {
            if (!(m instanceof Mesh)) return
            for (const v of worldVertices(m)) {
              if (v.y >= box.max.y - 0.001) top.set(`${round(v.x)}|${round(v.z)}`, [v.x, v.z])
            }
          })
          found.push({
            key: o.name.slice('asset-visual-'.length),
            min: box.min.toArray(),
            max: box.max.toArray(),
            top: [...top.values()],
          })
        })
        return found
      },
      flat() {
        const checks = new Map<string, FlatCheck>()
        walk(scene, (o) => {
          if (!(o instanceof Mesh) || !o.name.startsWith('flat:')) return
          const layer = o.name.slice('flat:'.length).split('|')[0] as FlatLayerKey
          const expectedY = LAYERS[layer]?.y
          if (expectedY === undefined) return
          const check = checks.get(layer) ?? { layer, expectedY, vertices: 0, worst: 0 }
          for (const v of worldVertices(o)) {
            check.vertices++
            check.worst = Math.max(check.worst, Math.abs(v.y - expectedY))
          }
          checks.set(layer, check)
        })
        return [...checks.values()]
      },
      near(x, z, radius) {
        const found = new Map<string, [number, number, number]>()
        const layout = scene.getObjectByName('layout')
        const skip = new Set([
          'layout-pick',
          'selection-outline',
          'hover-glow',
          'caption',
          'hover-label',
        ])
        const visit = (o: Object3D) => {
          if (!o.visible || skip.has(o.name)) return
          if (o instanceof Mesh) {
            for (const v of worldVertices(o)) {
              if (Math.hypot(v.x - x, v.z - z) <= radius)
                found.set(`${round(v.x)}|${round(v.y)}|${round(v.z)}`, [v.x, v.y, v.z])
            }
          }
          o.children.forEach(visit)
        }
        if (layout) visit(layout)
        const all = [...found.values()]
        const maxY = all.reduce((m, v) => Math.max(m, v[1]), -Infinity)
        const minY = all.reduce((m, v) => Math.min(m, v[1]), Infinity)
        const top = all
          .filter((v) => v[1] >= maxY - 0.001)
          .map((v) => [v[0], v[2]] as [number, number])
        const xs = all.map((v) => v[0])
        const zs = all.map((v) => v[2])
        const extent: [number, number, number, number] = [
          Math.min(...xs),
          Math.max(...xs),
          Math.min(...zs),
          Math.max(...zs),
        ]
        return { vertices: all.length, minY, maxY, extent, top }
      },
      orbit(deltaDeg) {
        const offset = camera.position.clone().sub(view.target)
        offset.applyAxisAngle(new Vector3(0, 1, 0), (deltaDeg * Math.PI) / 180)
        camera.position.copy(view.target).add(offset)
        camera.lookAt(view.target)
        camera.updateMatrixWorld(true)
        invalidate()
      },
      render() {
        gl.render(scene, camera)
        return stats()
      },
    }
    return () => {
      delete hooks.__castorScene
    }
  }, [gl, scene, camera, invalidate])

  return null
}
