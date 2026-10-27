import { Vector3 } from 'three'
import {
  CAMERA,
  PRESETS,
  clampDistance,
  fitBox,
  frameFootprint,
  keepAboveGround,
  presetByKey,
  presetForKey,
  zoomed,
} from './camera-presets'
import { resolve } from './camera-rig'
import { camera, useCameraStore } from './view-state'

describe('camera presets', () => {
  it('are the five of the ticket, in key order', () => {
    expect(PRESETS.map((p) => [p.key, p.label])).toEqual([
      ['overview', 'Overview'],
      ['top', 'Top'],
      ['yard', 'Yard'],
      ['quay', 'Quay'],
      ['ship', 'Ship'],
    ])
    expect(presetByKey('overview')).toMatchObject({
      position: [620, 840, 1180],
      target: [0, 0, -150],
    })
    expect(presetByKey('top')).toMatchObject({ position: [0, 2300, -149], target: [0, 0, -150] })
    expect(presetByKey('yard')).toMatchObject({
      position: [110, 170, 130],
      target: [-130, 0, -140],
    })
    expect(presetByKey('quay')).toMatchObject({ position: [-180, 100, 280], target: [-420, 8, 0] })
    expect(presetByKey('ship')).toMatchObject({ position: [-780, 80, 200], target: [-480, 12, 0] })
  })

  it('maps keys 1-5 only', () => {
    expect(presetForKey('1')?.key).toBe('overview')
    expect(presetForKey('5')?.key).toBe('ship')
    expect(presetForKey('0')).toBeUndefined()
    expect(presetForKey('6')).toBeUndefined()
    expect(presetForKey('a')).toBeUndefined()
    expect(presetForKey('12')).toBeUndefined()
  })

  it('every preset is above the ground and within the distance limits', () => {
    for (const p of PRESETS) {
      const pos = new Vector3(...p.position)
      const target = new Vector3(...p.target)
      const d = pos.distanceTo(target)
      expect(d).toBeGreaterThanOrEqual(CAMERA.minDistance)
      expect(d).toBeLessThanOrEqual(CAMERA.maxDistance)
      expect(keepAboveGround(pos, target).distanceTo(pos)).toBeLessThan(1e-9)
      expect(p.position[1]).toBeGreaterThan(0)
    }
  })

  it('has the lens and control values of the ticket', () => {
    expect(CAMERA).toMatchObject({
      fov: 30,
      near: 3,
      far: 20_000,
      damping: 0.08,
      minDistance: 12,
      maxDistance: 6000,
      zoomStep: 1.35,
      autoRotateSpeed: 1.2,
      flyMs: 600,
    })
    expect(CAMERA.maxPolar).toBeCloseTo(0.49 * Math.PI)
  })
})

describe('camera maths', () => {
  const target = new Vector3(0, 0, 0)

  it('zooms toward the target by 1.35 and clamps to 12..6000 m', () => {
    const p = new Vector3(0, 1000, 0)
    expect(zoomed(p, target, 1 / CAMERA.zoomStep).y).toBeCloseTo(1000 / 1.35)
    expect(zoomed(p, target, CAMERA.zoomStep).y).toBeCloseTo(1350)
    expect(zoomed(new Vector3(0, 5000, 0), target, 1.35).length()).toBeCloseTo(6000)
    expect(zoomed(new Vector3(0, 13, 0), target, 1 / 1.35).length()).toBeCloseTo(12)
    expect(clampDistance(new Vector3(0, 0, 0), target).length()).toBe(12)
  })

  it('can never put the camera below the ground', () => {
    for (const below of [-50, -1, 0, 0.5]) {
      const fixed = keepAboveGround(new Vector3(300, below, 400), target)
      const polar = Math.acos(fixed.y / fixed.length())
      expect(fixed.y).toBeGreaterThan(0)
      expect(polar).toBeLessThanOrEqual(CAMERA.maxPolar + 1e-9)
      expect(Math.atan2(fixed.x, fixed.z)).toBeCloseTo(Math.atan2(300, 400))
    }
    expect(keepAboveGround(new Vector3(0, -10, 0), target).y).toBeGreaterThan(0)
  })

  it('fits the whole port: the box lies inside the view', () => {
    const cam = { fov: 30, aspect: 16 / 9 }
    const small = fitBox({ x0: -500, x1: 500, z0: -300, z1: 0 }, cam)
    const d = new Vector3(...small.position).distanceTo(new Vector3(...small.target))
    expect(d).toBeGreaterThan(CAMERA.minDistance)
    expect(d * Math.tan((cam.fov * Math.PI) / 360) * cam.aspect).toBeGreaterThanOrEqual(500)
    // The full land is wider than the lens covers within 6,000 m: centred, as close as the controls allow
    const whole = fitBox({ x0: -3500, x1: 6000, z0: -1500, z1: 0 }, cam)
    const dw = new Vector3(...whole.position).distanceTo(new Vector3(...whole.target))
    expect(dw).toBeCloseTo(CAMERA.maxDistance, 6)
    expect(whole.target[0]).toBeCloseTo(1250)
  })

  it('frames an asset footprint', () => {
    const v = frameFootprint([100, 0, -50], 30, { fov: 30, aspect: 1.5 })
    expect(v.target).toEqual([100, 0, -50])
    const d = new Vector3(...v.position).distanceTo(new Vector3(...v.target))
    expect(d).toBeGreaterThan(30)
    expect(d).toBeLessThan(300)
    expect(v.position[1]).toBeGreaterThan(0)
  })
})

describe('camera commands', () => {
  const cam = { position: new Vector3(620, 840, 1180), fov: 30, aspect: 1.6 }
  const whole = { x0: -3500, x1: 6000, z0: -1500, z1: 0 }

  it('resolve every command to a view above ground inside the limits', () => {
    const t = new Vector3(0, 0, -150)
    const views = [
      resolve({ id: 1, type: 'preset', key: 'quay' }, cam, t, whole),
      resolve({ id: 2, type: 'whole' }, cam, t, whole),
      resolve({ id: 3, type: 'zoom', factor: 1 / 1.35 }, cam, t, whole),
      resolve({ id: 4, type: 'focus', centre: [0, 0, 0], radius: 20 }, cam, t, whole),
    ]
    for (const v of views) {
      expect(v).not.toBeNull()
      const pos = new Vector3(...v!.position)
      const d = pos.distanceTo(new Vector3(...v!.target))
      expect(pos.y).toBeGreaterThan(0)
      expect(d).toBeGreaterThanOrEqual(CAMERA.minDistance - 1e-6)
      expect(d).toBeLessThanOrEqual(CAMERA.maxDistance + 1e-6)
    }
    expect(resolve({ id: 5, type: 'preset', key: 'nope' }, cam, t, whole)).toBeNull()
  })

  it('are issued through the store; reset stops rotation and flies to Overview', () => {
    useCameraStore.setState({ autoRotate: false, command: null })
    camera.preset('yard')
    const first = useCameraStore.getState().command
    expect(first).toMatchObject({ type: 'preset', key: 'yard' })
    camera.zoomIn()
    expect(useCameraStore.getState().command!.id).toBeGreaterThan(first!.id)
    camera.toggleRotate()
    expect(useCameraStore.getState().autoRotate).toBe(true)
    camera.reset()
    expect(useCameraStore.getState()).toMatchObject({
      autoRotate: false,
      command: { type: 'preset', key: 'overview' },
    })
    camera.focusOn([1, 2, 3], 10)
    expect(useCameraStore.getState().command).toMatchObject({ type: 'focus', radius: 10 })
    camera.wholePort()
    expect(useCameraStore.getState().command).toMatchObject({ type: 'whole' })
    camera.zoomOut()
    expect(useCameraStore.getState().command).toMatchObject({ type: 'zoom', factor: 1.35 })
  })
})
