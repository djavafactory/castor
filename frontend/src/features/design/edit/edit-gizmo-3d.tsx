import { useFrame, useThree } from '@react-three/fiber'
import { useEffect, useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import {
  ConeGeometry,
  CylinderGeometry,
  Plane,
  Raycaster,
  TorusGeometry,
  Vector2,
  Vector3,
  type Camera,
  type Group,
  type Mesh,
} from 'three'
import { assetShapes } from '@/components/map/map-scene'
import { MIN_ENVELOPE_HEIGHT } from '@/components/scene/models/envelope'
import { DesignSelection } from '@/components/scene/stage/selection'
import { hex, lighten, palette } from '@/features/scene/palette'
import { useAssetTypes, useDraftLayout } from '@/lib/api'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { patchOf, type EditShape, type EditSubject } from './edit-model'
import { MOVE_THRESHOLD_PX, moveShape } from './edit-ops'
import { useEditPreview } from './edit-preview-store'
import { notChanged, type Verdict } from './edit-rules'
import { useEditSession } from './edit-session'
import {
  gizmoLabel,
  gizmoParts,
  heightOf,
  heightPatch,
  heightProblem,
  movesOnPress,
  resizeAlong,
  resizeHeight,
  rotateBy,
  shapeBox,
  type GizmoGesture,
  type GizmoPart,
  type HeightSpec,
} from './gizmo-ops'

/** A drag recomputes its preview (rules included) at most this often. */
export const THROTTLE_MS = 70
/** The hit meshes live on this three.js layer: the camera does not draw it and the scene's raycaster does not hit it. */
export const HIT_LAYER = 1
const RENDER_ORDER = 20
/** A line asset's selection box is at least this wide, metres. */
const MIN_BOX_SIDE_M = 1
const RAD = Math.PI / 180

/** The gizmo is 2 m at least, and a tenth of the camera distance, so it keeps its size on screen. */
export const gizmoSize = (distance: number) => Math.max(2, 0.1 * distance)

/** The local direction of an own axis (degrees counter-clockwise from local x) in scene metres: X = x, Z = -y. */
const sceneAxis = (rotationDeg: number) =>
  new Vector3(Math.cos(rotationDeg * RAD), 0, -Math.sin(rotationDeg * RAD))

/** The angle of a scene point around a centre, degrees counter-clockwise in local metres. */
const angleAround = (p: Vector3, centre: Vector3) =>
  Math.atan2(-(p.z - centre.z), p.x - centre.x) / RAD

/** A difference of two angles, folded into -180..180. */
const fold = (deg: number) => ((((deg + 180) % 360) + 360) % 360) - 180

interface Result {
  shape: EditShape
  heightM?: number
  verdict: Verdict
}

interface Drag {
  gesture: GizmoGesture
  subject: EditSubject
  pointerId: number
  startX: number
  startY: number
  moved: boolean
  plane: Plane
  start: Vector3
  centre: Vector3
  /** Own axis of a length or width drag, in scene metres. */
  axis: Vector3
  height: HeightSpec | null
  swept: number
  lastAngle: number
  last: { x: number; y: number; shift: boolean }
  lastRun: number
  timer: ReturnType<typeof setTimeout> | null
  result: Result | null
}

const COLOURS: Record<GizmoPart, string> = palette.gizmo

/**
 * The 3D editing gizmo (JOIN-01). For the selected asset of the draft, in Design with no tool and an unlocked layer: a
 * translucent blue box (footprint x height) and on top a gizmo that follows the heading: red arrow length, blue width,
 * green height, orange ring rotation. Pressing an asset selects it and moves it on a horizontal plane; empty ground
 * orbits the camera. The same rules as the 2D handles: a refused position turns the box red, the release snaps it back
 * with a toast, a valid one is one PATCH and one undo step.
 */
export function EditGizmo3D({ frameReady }: { frameReady: boolean }) {
  const types = useAssetTypes()
  const draft = useDraftLayout()
  const shapes = useMemo(
    () => (frameReady && draft.data ? assetShapes(draft.data.assets, types.data, palette.pad) : []),
    [frameReady, draft.data, types.data],
  )
  const session = useEditSession(shapes, frameReady)
  const { subject, typeMap } = session
  const preview = useEditPreview((s) => s.preview)
  const camera = useThree((s) => s.camera)
  const scene = useThree((s) => s.scene)
  const dom = useThree((s) => s.gl.domElement)
  const invalidate = useThree((s) => s.invalidate)
  const gizmo = useRef<Group>(null)
  const [hot, setHot] = useState<GizmoPart | null>(null)
  const [dragging, setDragging] = useState<GizmoGesture | null>(null)

  const type = subject ? typeMap.get(subject.typeCode) : undefined
  const height = useMemo(() => (subject && type ? heightOf(subject, type) : null), [subject, type])
  const mine = preview && subject && preview.assetId === subject.id ? preview : null
  const shape = mine?.shape ?? subject?.shape
  const box = subject && shape ? shapeBox(subject, shape) : null
  const top = Math.max(MIN_ENVELOPE_HEIGHT, mine?.heightM ?? height?.value ?? 0)
  const parts = useMemo(() => (subject ? gizmoParts(subject) : []), [subject])

  // What the native listeners read: the newest session without registering them again
  const live = useRef({ session, subject, height, box, top, parts })
  useEffect(() => {
    live.current = { session, subject, height, box, top, parts }
  })

  useEffect(() => {
    const host = dom.parentElement ?? dom
    const raycaster = new Raycaster()
    const ndc = new Vector2()
    let drag: Drag | null = null

    const rect = () => dom.getBoundingClientRect()
    const rayAt = (x: number, y: number) => {
      const r = rect()
      ndc.set(((x - r.left) / (r.width || 1)) * 2 - 1, -((y - r.top) / (r.height || 1)) * 2 + 1)
      raycaster.setFromCamera(ndc, camera as Camera)
      return raycaster.ray
    }
    const onPlane = (d: Drag, x: number, y: number) =>
      rayAt(x, y).intersectPlane(d.plane, new Vector3())

    const partAt = (x: number, y: number): GizmoPart | null => {
      const g = gizmo.current
      if (!g || !live.current.subject) return null
      g.updateMatrixWorld(true)
      rayAt(x, y)
      raycaster.layers.set(HIT_LAYER)
      const hits = raycaster
        .intersectObject(g, true)
        .map((h) => h.object.userData.part as GizmoPart | undefined)
      raycaster.layers.set(0)
      // Where an arrow crosses the ring, the arrow wins: the ring can be grabbed all the way round
      return hits.find((p) => p && p !== 'rotate') ?? hits.find(Boolean) ?? null
    }

    const assetAt = (x: number, y: number) => {
      const pick = scene.getObjectByName('layout-pick') as Mesh | undefined
      const assetOf = pick?.userData.assetAt as ((face: number) => string | undefined) | undefined
      if (!pick || !assetOf) return null
      rayAt(x, y)
      raycaster.layers.set(0)
      const hit = raycaster.intersectObject(pick, false)[0]
      const id = hit?.faceIndex == null ? undefined : assetOf(hit.faceIndex)
      return id && hit ? { id, point: hit.point } : null
    }

    const compute = (d: Drag): Result | null => {
      const p = onPlane(d, d.last.x, d.last.y)
      if (!p) return d.result
      const s = d.subject
      const { verdictOf } = live.current.session
      const of = (next: EditShape): Result => ({ shape: next, verdict: verdictOf(s, next) })
      switch (d.gesture) {
        case 'move':
          return of(moveShape(s, s.shape, p.x - d.start.x, -(p.z - d.start.z)))
        case 'length':
        case 'width':
          return of(resizeAlong(s, d.gesture, p.clone().sub(d.start).dot(d.axis)))
        case 'rotate':
          return of(rotateBy(s.shape, d.swept, d.last.shift))
        case 'height': {
          const spec = d.height!
          const value = resizeHeight(spec, p.y - d.start.y)
          const problem = heightProblem(spec, value)
          return {
            shape: s.shape,
            heightM: value,
            verdict: problem ? { ok: false, reason: problem } : { ok: true },
          }
        }
      }
    }

    const run = (d: Drag) => {
      d.timer = null
      d.lastRun = performance.now()
      const result = compute(d)
      if (!result) return
      d.result = result
      const r = rect()
      live.current.session.show(
        d.subject,
        result.shape,
        result.verdict,
        d.last.x - r.left,
        d.last.y - r.top,
        { view: '3d', ...(result.heightM === undefined ? {} : { heightM: result.heightM }) },
      )
      invalidate()
    }

    const swallowClick = (event: MouseEvent) => event.stopPropagation()

    const stop = () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      window.removeEventListener('pointercancel', onCancel)
      window.removeEventListener('keydown', onKey)
      if (drag?.timer) clearTimeout(drag.timer)
      drag = null
      setDragging(null)
      dom.style.cursor = ''
      // The click that follows this release belongs to the gizmo, not to the scene
      setTimeout(() => host.removeEventListener('click', swallowClick, true), 0)
    }
    const clear = () => useEditPreview.getState().setPreview(null)

    function begin(
      event: PointerEvent,
      d: Omit<Drag, 'moved' | 'last' | 'lastRun' | 'timer' | 'result' | 'swept' | 'lastAngle'>,
    ) {
      event.stopPropagation()
      event.preventDefault()
      drag = {
        ...d,
        moved: false,
        swept: 0,
        lastAngle: angleAround(d.start, d.centre),
        last: { x: event.clientX, y: event.clientY, shift: event.shiftKey },
        lastRun: 0,
        timer: null,
        result: null,
      }
      setDragging(d.gesture)
      host.addEventListener('click', swallowClick, true)
      window.addEventListener('pointermove', onMove)
      window.addEventListener('pointerup', onUp)
      window.addEventListener('pointercancel', onCancel)
      window.addEventListener('keydown', onKey)
    }

    function onDown(event: PointerEvent) {
      if (event.button !== 0 || drag) return
      const { subject: s, height: spec, box: b, top: h } = live.current
      const base = { pointerId: event.pointerId, startX: event.clientX, startY: event.clientY }
      const part = partAt(event.clientX, event.clientY)
      if (part && s && b) {
        const centre = new Vector3(b.centre[0], h, -b.centre[1])
        let plane: Plane
        if (part === 'height') {
          // A vertical plane through the gizmo, facing the camera
          const normal = camera.position.clone().sub(centre).setY(0)
          if (normal.lengthSq() < 1e-9) camera.getWorldDirection(normal).setY(0).negate()
          if (normal.lengthSq() < 1e-9) normal.set(0, 0, 1)
          plane = new Plane().setFromNormalAndCoplanarPoint(normal.normalize(), centre)
        } else {
          plane = new Plane(new Vector3(0, 1, 0), -h)
        }
        const start = rayAt(event.clientX, event.clientY).intersectPlane(plane, new Vector3())
        if (!start) return
        begin(event, {
          ...base,
          gesture: part,
          subject: s,
          plane,
          start,
          centre,
          axis: sceneAxis(b.rotationDeg + (part === 'width' ? 90 : 0)),
          height: spec,
        })
        return
      }
      const hit = assetAt(event.clientX, event.clientY)
      const target = hit && live.current.session.subjectFor(hit.id)
      if (!hit || !target || !movesOnPress(target)) return
      useSelectionStore.getState().select(hit.id)
      begin(event, {
        ...base,
        gesture: 'move',
        subject: target,
        plane: new Plane(new Vector3(0, 1, 0), -hit.point.y),
        start: hit.point.clone(),
        centre: hit.point.clone(),
        axis: new Vector3(1, 0, 0),
        height: null,
      })
    }

    function onMove(event: PointerEvent) {
      const d = drag
      if (!d || event.pointerId !== d.pointerId) return
      d.last = { x: event.clientX, y: event.clientY, shift: event.shiftKey }
      if (
        !d.moved &&
        Math.hypot(event.clientX - d.startX, event.clientY - d.startY) < MOVE_THRESHOLD_PX
      ) {
        return
      }
      d.moved = true
      dom.style.cursor = 'grabbing'
      if (d.gesture === 'rotate') {
        // Every move counts towards the swept angle, so a fast turn past 180 degrees is not lost to the throttle
        const p = onPlane(d, event.clientX, event.clientY)
        if (p) {
          const angle = angleAround(p, d.centre)
          d.swept += fold(angle - d.lastAngle)
          d.lastAngle = angle
        }
      }
      const wait = THROTTLE_MS - (performance.now() - d.lastRun)
      if (wait <= 0) run(d)
      else if (!d.timer) d.timer = setTimeout(() => run(d), wait)
    }

    function onUp(event: PointerEvent) {
      const d = drag
      if (!d || event.pointerId !== d.pointerId) return
      stop()
      if (!d.moved) {
        clear()
        return
      }
      const result = compute(d)
      const s = d.subject
      const same =
        !result ||
        (result.heightM === undefined
          ? JSON.stringify(result.shape) === JSON.stringify(s.shape)
          : result.heightM === d.height?.value)
      if (!result || same) {
        clear()
        return
      }
      if (!result.verdict.ok) {
        clear()
        toast(notChanged(result.verdict.reason))
        return
      }
      const changes =
        result.heightM === undefined
          ? patchOf(s, result.shape)
          : heightPatch(s, d.height!, result.heightM)
      void live.current.session
        .commit(s, changes, gizmoLabel(d.gesture, s.name), notChanged)
        .finally(clear)
    }

    function onCancel(event: PointerEvent) {
      if (drag && event.pointerId === drag.pointerId) {
        stop()
        clear()
      }
    }

    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape' && drag) {
        stop()
        clear()
      }
    }

    // Hovering a part lights it up and shows a grab cursor
    function onHover(event: PointerEvent) {
      if (drag) return
      const part = partAt(event.clientX, event.clientY)
      setHot((current) => (current === part ? current : part))
      dom.style.cursor = part ? 'grab' : ''
    }

    host.addEventListener('pointerdown', onDown, true)
    host.addEventListener('pointermove', onHover)
    // Development only (the end-to-end tests): where the gizmo parts are on the screen; not in a production build
    const hooks = globalThis as { __castorGizmo?: unknown }
    if (import.meta.env.DEV) {
      const toClient = (p: Vector3) => {
        const v = p.clone().project(camera)
        const r = rect()
        return { x: r.left + ((v.x + 1) / 2) * r.width, y: r.top + ((1 - v.y) / 2) * r.height }
      }
      /** A grab point of a part in the gizmo's own frame, `amount` along its axis (metres, or degrees round the ring). */
      const grab = (part: GizmoPart, amount = 0) => {
        const g = gizmo.current
        if (!g) return null
        g.updateMatrixWorld(true)
        const s = g.scale.x
        const at =
          part === 'rotate'
            ? ((a) => new Vector3(0.8 * Math.cos(a), 0, -0.8 * Math.sin(a)))(
                ((225 + amount) * Math.PI) / 180,
              )
            : part === 'length'
              ? new Vector3(0.8 + amount / s, 0, 0)
              : part === 'width'
                ? new Vector3(0, 0, -0.8 - amount / s)
                : new Vector3(0, 0.8 + amount / s, 0)
        return toClient(g.localToWorld(at))
      }
      /** A point on the top of the selected box with no gizmo part under it, and that point moved by local metres. */
      const body = (dx: number, dy: number) => {
        const { box: b, top: h } = live.current
        if (!b) return null
        const [c, s] = [Math.cos(b.rotationDeg * RAD), Math.sin(b.rotationDeg * RAD)]
        for (const [u, v] of [
          [-0.35, -0.3],
          [-0.35, 0.3],
          [0.35, -0.3],
          [-0.2, -0.2],
          [0.2, -0.3],
        ] as const) {
          const x = b.centre[0] + u * b.length * c - v * b.width * s
          const y = b.centre[1] + u * b.length * s + v * b.width * c
          const from = toClient(new Vector3(x, h, -y))
          if (partAt(from.x, from.y)) continue
          return { from, to: toClient(new Vector3(x + dx, h, -(y + dy))) }
        }
        return null
      }
      hooks.__castorGizmo = { grab, body, hit: partAt }
    }
    return () => {
      delete hooks.__castorGizmo
      host.removeEventListener('pointerdown', onDown, true)
      host.removeEventListener('pointermove', onHover)
      if (drag) {
        stop()
        clear()
      }
    }
  }, [dom, camera, scene, invalidate])

  const scratch = useMemo(() => new Vector3(), [])
  useFrame(({ camera: c }) => {
    const g = gizmo.current
    if (!g) return
    g.getWorldPosition(scratch)
    const size = gizmoSize(c.position.distanceTo(scratch))
    if (Math.abs(g.scale.x - size) > 1e-6) {
      g.scale.setScalar(size)
      invalidate()
    }
  })

  if (!subject || !box) return null
  const rotationY = box.rotationDeg * RAD
  const centre: [number, number, number] = [box.centre[0], top, -box.centre[1]]
  const lit = (part: GizmoPart) => hot === part || dragging === part
  return (
    <group name="edit-gizmo-3d">
      <DesignSelection
        size={[Math.max(MIN_BOX_SIDE_M, box.length), top, Math.max(MIN_BOX_SIDE_M, box.width)]}
        position={[box.centre[0], top / 2, -box.centre[1]]}
        rotationY={rotationY}
        invalid={mine !== null && !mine.valid}
      />
      <group ref={gizmo} name="gizmo" position={centre} rotation={[0, rotationY, 0]}>
        {parts.map((part) =>
          part === 'rotate' ? (
            <Ring key={part} lit={lit(part)} />
          ) : (
            <Arrow key={part} part={part} lit={lit(part)} />
          ),
        )}
      </group>
    </group>
  )
}

/** +Y of a cylinder turned onto the own axis of the part: length +X, width -Z (local y), height +Y. */
const TURN: Record<Exclude<GizmoPart, 'rotate'>, [number, number, number]> = {
  length: [0, 0, -Math.PI / 2],
  width: [-Math.PI / 2, 0, 0],
  height: [0, 0, 0],
}

function colourOf(part: GizmoPart, lit: boolean) {
  const c = hex(COLOURS[part])
  return lit ? lighten(c, 0.35) : c
}

function OnTop({ colour }: { colour: number }) {
  return <meshBasicMaterial color={colour} depthTest={false} depthWrite={false} transparent />
}

/** Puts a hit mesh on the hit layer, out of sight and out of the scene's raycaster. */
const hitLayer = (mesh: Mesh | null) => {
  mesh?.layers.set(HIT_LAYER)
}

function Arrow({ part, lit }: { part: Exclude<GizmoPart, 'rotate'>; lit: boolean }) {
  const geometry = useMemo(
    () => ({
      shaft: new CylinderGeometry(0.03, 0.03, 0.85, 12),
      tip: new ConeGeometry(0.09, 0.24, 16),
      hit: new CylinderGeometry(0.16, 0.16, 1.3, 8),
    }),
    [],
  )
  useEffect(() => () => Object.values(geometry).forEach((g) => g.dispose()), [geometry])
  const colour = colourOf(part, lit)
  return (
    <group name={`gizmo-${part}`} rotation={TURN[part]}>
      <mesh geometry={geometry.shaft} position={[0, 0.575, 0]} renderOrder={RENDER_ORDER}>
        <OnTop colour={colour} />
      </mesh>
      <mesh geometry={geometry.tip} position={[0, 1.12, 0]} renderOrder={RENDER_ORDER}>
        <OnTop colour={colour} />
      </mesh>
      <mesh
        ref={hitLayer}
        name={`gizmo-hit-${part}`}
        geometry={geometry.hit}
        position={[0, 0.65, 0]}
        userData={{ part }}
      />
    </group>
  )
}

function Ring({ lit }: { lit: boolean }) {
  const geometry = useMemo(
    () => ({
      ring: new TorusGeometry(0.8, 0.025, 8, 64),
      hit: new TorusGeometry(0.8, 0.14, 8, 32),
    }),
    [],
  )
  useEffect(() => () => Object.values(geometry).forEach((g) => g.dispose()), [geometry])
  return (
    <group name="gizmo-rotate" rotation={[-Math.PI / 2, 0, 0]}>
      <mesh geometry={geometry.ring} renderOrder={RENDER_ORDER}>
        <OnTop colour={colourOf('rotate', lit)} />
      </mesh>
      <mesh
        ref={hitLayer}
        name="gizmo-hit-rotate"
        geometry={geometry.hit}
        userData={{ part: 'rotate' }}
      />
    </group>
  )
}
