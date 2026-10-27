// Regenerates backend/castor-core/src/main/resources/seed/westports/layout-v1.geojson (CORE-04, Part 6).
// Everything is a SYNTHETIC placeholder placed in the SETUP-03 quay frame (x along the quay, y inland).
// The frame formulas are written out here independently of the backend QuayFrame, as in generate-coordinate-fixtures.mjs.
// Run: node scripts/generate-westports-layout.mjs
import { writeFileSync } from 'node:fs'

const frame = { lon0: 101.3066304, lat0: 2.9534057, rotDeg: -120.299 }
const extent = { lonMin: 101.26, lonMax: 101.36, latMin: 2.9, latMax: 3.02 }
const RAD = Math.PI / 180
const mLat = 111132.92 - 559.82 * Math.cos(2 * frame.lat0 * RAD) + 1.175 * Math.cos(4 * frame.lat0 * RAD)
const mLon = 111412.84 * Math.cos(frame.lat0 * RAD) - 93.5 * Math.cos(3 * frame.lat0 * RAD)
const cR = Math.cos(frame.rotDeg * RAD)
const sR = Math.sin(frame.rotDeg * RAD)
const toLocal = (lon, lat) => {
  const e = (lon - frame.lon0) * mLon
  const n = (lat - frame.lat0) * mLat
  return [e * cR + n * sR, -e * sR + n * cR]
}
const toLonLat = (x, y) => [frame.lon0 + (x * cR - y * sR) / mLon, frame.lat0 + (x * sR + y * cR) / mLat]
const r7 = (v) => Number(v.toFixed(7))
const r3 = (v) => Number(v.toFixed(3))
const mod360 = (d) => ((d % 360) + 360) % 360
const ll = (x, y) => toLonLat(x, y).map(r7)
const rotationToHeading = (rot) => mod360(90 - rot - frame.rotDeg)
const headingToRotation = (h) => {
  const r = mod360(90 - h - frame.rotDeg)
  return r > 180 ? r - 360 : r
}

// Footprints are built from the ROUNDED anchor and heading, so rebuilding from the stored fields matches to < 1 cm.
function rectRing(anchorLL, heading, length, width) {
  const [ax, ay] = toLocal(...anchorLL)
  const rot = headingToRotation(heading) * RAD
  const c = Math.cos(rot)
  const s = Math.sin(rot)
  const hl = length / 2
  const hw = width / 2
  const ring = [[-hl, -hw], [hl, -hw], [hl, hw], [-hl, hw]].map(([x, y]) => ll(ax + x * c - y * s, ay + x * s + y * c))
  return [...ring, ring[0]]
}
function circleRing(anchorLL, radius) {
  const [ax, ay] = toLocal(...anchorLL)
  const ring = Array.from({ length: 48 }, (_, i) => {
    const t = (2 * Math.PI * i) / 48
    return ll(ax + radius * Math.cos(t), ay + radius * Math.sin(t))
  })
  return [...ring, ring[0]]
}
function lineMid(points) {
  const lens = points.slice(1).map((p, i) => Math.hypot(p[0] - points[i][0], p[1] - points[i][1]))
  let half = lens.reduce((a, b) => a + b, 0) / 2
  for (let i = 0; i < lens.length; i++) {
    if (half <= lens[i]) {
      const f = half / lens[i]
      return [points[i][0] + f * (points[i + 1][0] - points[i][0]), points[i][1] + f * (points[i + 1][1] - points[i][1])]
    }
    half -= lens[i]
  }
  return points.at(-1)
}
function centroid(points) {
  let a = 0
  let cx = 0
  let cy = 0
  for (let i = 0; i < points.length; i++) {
    const [x1, y1] = points[i]
    const [x2, y2] = points[(i + 1) % points.length]
    const k = x1 * y2 - x2 * y1
    a += k
    cx += (x1 + x2) * k
    cy += (y1 + y2) * k
  }
  return [cx / (3 * a), cy / (3 * a)]
}

const features = []
const codes = new Set()
const links = [] // [from, to, kind]
const NOTE = 'Synthetic placeholder, not a survey'
const provenance = { source: 'SYNTHETIC', accuracy: 'APPROXIMATE', capturedOn: '2026-10-21', note: NOTE }

function add(code, type, name, geometry, anchor, props = {}, details = {}) {
  if (codes.has(code)) throw new Error('duplicate code ' + code)
  codes.add(code)
  features.push({
    type: 'Feature',
    id: code,
    geometry,
    properties: {
      code,
      type,
      name,
      anchor,
      headingDeg: props.headingDeg ?? null,
      lengthM: props.lengthM ?? null,
      widthM: props.widthM ?? null,
      heightM: props.heightM ?? null,
      radiusM: props.radiusM ?? null,
      lineWidthM: props.lineWidthM ?? null,
      elevationM: 0,
      storageZoneCode: null,
      details,
      links: [],
      provenance,
    },
  })
}

/** Rectangle footprint (POINT and RECT types). rot is degrees counter-clockwise from local +X. */
function rect(code, type, name, x, y, length, width, height, { rot = 0, details = {} } = {}) {
  const anchor = ll(x, y)
  const headingDeg = r3(rotationToHeading(rot))
  const h = headingDeg >= 360 ? 0 : headingDeg
  add(code, type, name, { type: 'Polygon', coordinates: [rectRing(anchor, h, length, width)] }, anchor,
    { headingDeg: h, lengthM: length, widthM: width, heightM: height ?? null }, details)
}
function circle(code, type, name, x, y, radius, height, details = {}) {
  const anchor = ll(x, y)
  add(code, type, name, { type: 'Polygon', coordinates: [circleRing(anchor, radius)] }, anchor,
    { radiusM: radius, heightM: height }, details)
}
function line(code, type, name, pts, lineWidthM, heightM, details = {}) {
  const coords = pts.map(([x, y]) => ll(x, y))
  add(code, type, name, { type: 'LineString', coordinates: coords }, ll(...lineMid(pts)),
    { lineWidthM, heightM: heightM ?? null }, details)
}
function polygon(code, type, name, pts, heightM, details = {}) {
  const ring = pts.map(([x, y]) => ll(x, y))
  const [cx, cy] = centroid(pts)
  add(code, type, name, { type: 'Polygon', coordinates: [[...ring, ring[0]]] }, ll(cx, cy), { heightM: heightM ?? null }, details)
}
const box = (cx, cy, w, h) => [[cx - w / 2, cy - h / 2], [cx + w / 2, cy - h / 2], [cx + w / 2, cy + h / 2], [cx - w / 2, cy + h / 2]]
const link = (from, to, kind) => links.push([from, to, kind])
const mobile = (uncertaintyM) => ({ mobile: { positionMode: 'PARKED', uncertaintyM } })

// ---- Berths (QUAY_SEGMENT on y = 0) ----
const CONVENTIONAL = ['BREAKBULK', 'DRY_BULK']
const berths = [
  ['B1', 'Berth 1', -390, 400, 16.5],
  ['B2', 'Berth 2', 10, 400, 16.5],
  ['B3', 'Berth 3', 360, 300, 13],
  ['B4', 'Berth 4', 610, 200, 10.5],
  ['B0', 'Berth 0 (general cargo)', 920, 190, 10],
]
for (let i = 0; i < 8; i++) berths.push([`CB${i + 1}`, `Container berth ${i + 1}`, 1250 + 400 * i, 400, 16.5])
for (const [code, name, x, len, depth] of berths) {
  const container = code.startsWith('CB')
  const geo = [[x - len / 2, 0], [x + len / 2, 0]]
  const coords = geo.map(([px, py]) => ll(px, py))
  add(code, 'BERTH', name, { type: 'LineString', coordinates: coords }, ll(x, 0), { lengthM: len },
    { berth: { depthM: depth, maxLoaM: len, cargoFamilies: container ? ['CONTAINER'] : CONVENTIONAL } })
}
const berthX = Object.fromEntries(berths.map(([c, , x, len]) => [c, [x, len]]))

// ---- Rail quay cranes ----
const qc = [[-470, 'B1'], [-390, 'B1'], [-310, 'B1'], [-70, 'B2'], [10, 'B2'], [90, 'B2'], [300, 'B3'], [380, 'B3'], [610, 'B4']]
qc.forEach(([x, b], i) => {
  const code = `QC${i + 1}`
  rect(code, 'QUAY_CRANE_RAIL', `Quay crane ${i + 1}`, x, 10, 24, 16, 52,
    { details: { crane: { gaugeM: 16, outreachM: 65, backreachM: 32 } } })
  link(code, b, 'SERVES')
})

// ---- Storage pads ----
const CARGO = {
  COAL: { density: 0.85, form: 'PILE', repose: 37 },
  IRON: { density: 2.2, form: 'PILE', repose: 35 },
  BAUX: { density: 1.2, form: 'PILE', repose: 35 },
  GRAIN: { density: 0.77, form: 'SILO' },
  STEEL: { density: 3.0, form: 'STACK', maxStack: 5 },
  TIMBER: { density: 0.55, form: 'STACK', maxStack: 6 },
}
const K = (rx, rz) => (1 + 4 * ((1 + rx) / 2) * ((1 + rz) / 2) + rx * rz) / 6
function slotTonnes(cargo, bayPitch, rowPitch, maxHeight) {
  const c = CARGO[cargo]
  const L = bayPitch * 0.94
  const W = rowPitch * 0.94
  if (c.form === 'PILE') {
    const h = Math.min(maxHeight, Math.tan(c.repose * RAD) * ((1 - 0.15) / 2) * W)
    return K(0.55, 0.15) * L * W * h * c.density
  }
  if (c.form === 'STACK') return K(0.92, 0.92) * L * W * Math.min(maxHeight, c.maxStack) * c.density
  const d = Math.min(L, W)
  return (Math.PI / 4) * d * d * 2 * maxHeight * c.density
}
const padCargo = [
  ...Array(6).fill('COAL'), ...Array(7).fill('GRAIN'), ...Array(3).fill('IRON'),
  ...Array(3).fill('BAUX'), ...Array(2).fill('STEEL'), ...Array(2).fill('TIMBER'),
]
const pads = []
;[['A', 90], ['B', 150], ['C', 210]].forEach(([row, y]) => {
  for (let i = 0; i < 7; i++) pads.push([`${row}${i + 1}`, -420 + 140 * i, y, 0])
})
pads.push(['R1', 612, 250, 90], ['R2', 652, 250, 90])
pads.forEach(([id, x, y, rot], i) => {
  const cargo = padCargo[i]
  const capacity = Math.round(slotTonnes(cargo, 62, 34, 10) * 2)
  rect(`PAD-${id}`, 'STORAGE_PAD', `Storage pad ${id}`, x, y, 124, 34, null, {
    rot,
    details: {
      storagePad: { bays: 2, rows: 1, bayPitchM: 62, rowPitchM: 34, maxHeightM: 10 },
      storage: {
        capacityValue: capacity, capacityUnit: 't', usableRatio: 1,
        designFill: [0.35, 0.5, 0.65, 0.8][i % 4], cargoTypeCode: cargo,
      },
    },
  })
})

// ---- Terminal boundaries and surface areas ----
rect('T1', 'TERMINAL_BOUNDARY', 'Conventional and Bulk Terminal', 200, 140, 1650, 280, null)
rect('T6', 'TERMINAL_BOUNDARY', 'Westports container terminals (CT)', 2650, 150, 3200, 300, null)
rect('AR1', 'SURFACE_AREA', 'Gate plaza', -8, 347, 120, 76, null, { details: { surface: { surfaceCode: 'CONCRETE' } } })
rect('AR2', 'SURFACE_AREA', 'Truck park', 300, 300, 150, 56, null, { details: { surface: { surfaceCode: 'ASPHALT' } } })

// ---- Road segments ----
rect('R-APRON', 'ROAD_SEGMENT', 'Apron road', 160, 30, 1720, 30, null)
for (let i = 0; i < 3; i++) rect(`R-LANE${i + 1}`, 'ROAD_SEGMENT', `Lane ${i + 1}`, 0, 120 + 60 * i, 980, 14, null)
for (let i = 0; i < 6; i++) rect(`R-V${i + 1}`, 'ROAD_SEGMENT', `Cross lane ${i + 1}`, -350 + 140 * i, 135, 240, 12, null, { rot: 90 })
rect('R-LINKW', 'ROAD_SEGMENT', 'Link road west', -490, 135, 240, 12, null, { rot: 90 })
rect('R-LINKE', 'ROAD_SEGMENT', 'Link road east', 490, 135, 240, 12, null, { rot: 90 })
rect('R-GATE', 'ROAD_SEGMENT', 'Gate road', -8, 330, 200, 16, null, { rot: 90 })
rect('R-EAST', 'ROAD_SEGMENT', 'East road', 545, 185, 290, 20, null, { rot: 90 })

// ---- Gates ----
rect('G1', 'GATE', 'Main gate', -8, 347, 14, 8, 4.5, { rot: 90 })
rect('G3', 'GATE', 'Container terminal gate', 1050, 330, 14, 8, 4)
rect('WB1', 'WEIGHBRIDGE', 'Weighbridge', -8, 300, 20, 4, 0.3, { rot: 90 })
rect('BLD-GT', 'BUILDING', 'Gate house', 30, 340, 30, 12, 6)

// ---- Dry bulk chain ----
for (let i = 0; i < 3; i++) {
  circle(`SL${i + 1}`, 'SILO', `Silo ${i + 1}`, 730 + 32 * i, 162, 9, 26, {
    storage: { capacityValue: 5094, capacityUnit: 't', usableRatio: 0.95, designFill: 0.6, cargoTypeCode: 'GRAIN' },
  })
}
rect('HP1', 'HOPPER', 'Hopper 1', 640, 68, 14, 12, 9, { details: { storage: { capacityValue: 120, capacityUnit: 't', usableRatio: 1, designFill: 0.3, cargoTypeCode: null } } })
line('CV1', 'CONVEYOR', 'Conveyor 1', [[640, 68], [640, 100], [730, 100], [730, 150]], 2.4, 7, { flow: { rateValue: 700, rateUnit: 't/h' } })
rect('TT1', 'TRANSFER_TOWER', 'Transfer tower 1', 730, 146, 8, 8, 24)
link('HP1', 'CV1', 'FEEDS')
link('CV1', 'TT1', 'FEEDS')
for (let i = 1; i <= 3; i++) link('TT1', `SL${i}`, 'FEEDS')

// ---- Warehouse, laydown yard, quay staging ----
polygon('WHL', 'WAREHOUSE_POLY', 'Warehouse L (steel store)',
  [[780, 40], [900, 40], [900, 70], [820, 70], [820, 110], [780, 110]], 9, {
    storage: { capacityValue: 5200, capacityUnit: 't', usableRatio: 0.75, designFill: 0.5, cargoTypeCode: 'STEEL' },
    warehouse: { roofRiseM: 2.5, dockDoors: 8 },
  })
polygon('YD1', 'LAYDOWN_YARD', 'Laydown yard 1', box(900, 190, 140, 80), null, {
  surface: { surfaceCode: 'CONCRETE' },
  storage: { capacityValue: 400, capacityUnit: 'pcs', usableRatio: 1, designFill: 0.3, cargoTypeCode: null },
})
rect('QS1', 'QUAY_STAGING', 'Quay staging 1', -545, 60, 80, 34, null, {
  details: { storage: { capacityValue: 300, capacityUnit: 'pcs', usableRatio: 1, designFill: 0.2, cargoTypeCode: null } },
})

// ---- Liquid bulk tank farms ----
const FARM_ZONE = ['LIQUID_TANK', 'PIPELINE', 'PUMP_STATION', 'EXCLUSION_ZONE', 'BUND_WALL', 'PIPE_RACK', 'LOADING_ARM', 'TANK_TRUCK', 'FIRE_ACCESS']
const farms = [
  { n: 1, cx: -2482, cy: 538, tanks: [['TK1', -18, -22, 16, 16, 'OIL'], ['TK1A', 18, -22, 15, 14, 'OIL'], ['TK1B', -18, 22, 15, 14, 'CHEM'], ['TK1C', 18, 22, 16, 16, 'EDOIL']] },
  { n: 2, cx: -2912, cy: 670, tanks: [['TK2', -18, -22, 16, 16, 'OIL'], ['TK2A', 18, -22, 15, 14, 'CHEM'], ['TK2B', -18, 22, 15, 14, 'EDOIL'], ['TK2C', 18, 22, 16, 16, 'OIL']] },
]
const TANK_DENSITY = { OIL: 0.85, CHEM: 1.1, EDOIL: 0.92 }
void TANK_DENSITY
for (const f of farms) {
  for (const [code, dx, dy, r, h, cargo] of f.tanks) {
    const m3 = Math.round(Math.PI * r * r * h)
    circle(code, 'LIQUID_TANK', `Tank ${code.slice(2)}`, f.cx + dx, f.cy + dy, r, h, {
      storage: { capacityValue: m3, capacityUnit: 'm3', usableRatio: 0.95, designFill: 0.6, cargoTypeCode: cargo },
    })
  }
  polygon(`BUND${f.n}`, 'BUND_WALL', `Bund wall ${f.n}`, box(f.cx, f.cy, 90, 80), 1.2)
  line(`PR${f.n}`, 'PIPE_RACK', `Pipe rack ${f.n}`, [[f.cx - 45, f.cy + 50], [f.cx + 45, f.cy + 50]], 2.4, 5)
  rect(`PS${f.n}`, 'PUMP_STATION', `Pump station ${f.n}`, f.cx + 60, f.cy, 9, 6, 4)
  line(`PL${f.n}`, 'PIPELINE', `Pipeline ${f.n}`, [[f.cx + 60, f.cy + 5], [f.cx + 60, f.cy + 50], [f.cx - 45, f.cy + 50]], 0.9, 1.4,
    { flow: { rateValue: 400, rateUnit: 'm3/h' } })
  polygon(`EXZ${f.n}`, 'EXCLUSION_ZONE', `Exclusion zone ${f.n}`, box(f.cx + 10, f.cy + 5, 150, 130), null, { zone: { allowedTypes: FARM_ZONE } })
}
rect('LA1', 'LOADING_ARM', 'Loading arm 1', -2482 + 75, 538 + 22, 8, 4, 10)
rect('LA2', 'LOADING_ARM', 'Loading arm 2', -2482 + 75, 538 - 22, 8, 4, 10)
rect('LA3', 'LOADING_ARM', 'Loading arm 3', -2912 + 75, 670, 8, 4, 10)

// ---- Container terminal (static) ----
const fnv = (s) => {
  let h = 0x811c9dc5
  for (const ch of s) h = Math.imul(h ^ ch.charCodeAt(0), 0x01000193) >>> 0
  return h
}
for (let b = 1; b <= 8; b++) {
  const bx = berthX[`CB${b}`][0]
  ;[['L', -90], ['R', 90]].forEach(([side, dx]) => {
    ;[110, 170, 230].forEach((y, i) => {
      const yard = `CY-${b}${side}${i + 1}`
      const fill = r3(0.4 + ((fnv(yard) % 46) / 100))
      rect(yard, 'CONTAINER_YARD', `Container yard ${b}${side}${i + 1}`, bx + dx, y, 150, 46, 13,
        { details: { containerYard: { maxTiers: 5, designFill: fill, capacityTeu: 3000 } } })
      rect(`RTG-${b}${side}${i + 1}`, 'RTG', `RTG ${b}${side}${i + 1}`, bx + dx, y, 12, 24, 18)
      link(`RTG-${b}${side}${i + 1}`, yard, 'SERVES')
    })
  })
  ;[['A', -130], ['B', 0], ['C', 130]].forEach(([k, dx]) => {
    const code = `STS-CB${b}-${k}`
    rect(code, 'STS_CRANE', `STS crane CB${b}-${k}`, bx + dx, 11, 16, 22, 55,
      { details: { crane: { outreachM: 65, backreachM: 30 } } })
    link(code, `CB${b}`, 'SERVES')
  })
}

// ---- Marine: fenders, bollards, RoRo ----
const BOLLARDS = { B1: 10, B2: 10, B3: 7, B4: 5, B0: 4 }
for (const [code, , x, len] of berths) {
  line(`FND-${code}`, 'FENDER', `Fender line ${code}`, [[x - len / 2 + 22, 0.4], [x + len / 2 - 22, 0.4]], 1.1, 1.2)
}
for (const [code, n] of Object.entries(BOLLARDS)) {
  const [x, len] = berthX[code]
  for (let i = 0; i < n; i++) {
    const bx = x - len / 2 + 15 + ((len - 30) * i) / (n - 1)
    rect(`BOL-${code}-${String(i + 1).padStart(2, '0')}`, 'BOLLARD', `Bollard ${code}-${i + 1}`, bx, 1.5, 1.2, 1.2, 0.9)
  }
}
rect('RAMP1', 'RORO_RAMP', 'RoRo ramp', 1000, 8, 24, 10, 3)
rect('PY1', 'PARKING_YARD', 'RoRo parking yard', 970, 120, 90, 50, null,
  { details: { storage: { capacityValue: 300, capacityUnit: 'veh', usableRatio: 1, designFill: 0.4, cargoTypeCode: 'VEH' } } })
rect('IB1', 'INSPECTION_BAY', 'Inspection bay', 970, 80, 30, 12, 5,
  { details: { storage: { capacityValue: 4, capacityUnit: 'veh', usableRatio: 1, designFill: 0, cargoTypeCode: null } } })
line('DL1', 'DISPATCH_LANE', 'Dispatch lane', [[950, 70], [1010, 70], [1010, 160]], 6, null)

// ---- Mobile equipment (PARKED) ----
rect('MHC1', 'MOBILE_HARBOUR_CRANE', 'Mobile harbour crane 1', 920, 15, 18, 9, 28,
  { details: { crane: { reachM: 32 }, ...mobile(2) } })
link('MHC1', 'B0', 'SERVES')
const parked = [
  ['FL1', 'FORKLIFT', 'Forklift 1', -300, 60, 4.2, 2.2, 2.6, 1],
  ['FL2', 'FORKLIFT', 'Forklift 2', -280, 60, 4.2, 2.2, 2.6, 1],
  ['RS1', 'REACH_STACKER', 'Reach stacker 1', 1300, 60, 9.5, 4, 4.5, 3],
  ['WL1', 'WHEEL_LOADER', 'Wheel loader 1', 560, 110, 9, 3.4, 3.6, 3],
  ['TT-01', 'TERMINAL_TRACTOR', 'Terminal tractor 1', 1500, 60, 6, 2.5, 3.2, 5],
  ['TT-02', 'TERMINAL_TRACTOR', 'Terminal tractor 2', 1520, 60, 6, 2.5, 3.2, 5],
  ['TT-03', 'TERMINAL_TRACTOR', 'Terminal tractor 3', 1900, 60, 6, 2.5, 3.2, 5],
  ['TT-04', 'TERMINAL_TRACTOR', 'Terminal tractor 4', 1920, 60, 6, 2.5, 3.2, 5],
  ['FB1', 'FLATBED_TRUCK', 'Flatbed truck 1', 250, 300, 12, 2.5, 3.5, 8],
  ['FB2', 'FLATBED_TRUCK', 'Flatbed truck 2', 270, 300, 12, 2.5, 3.5, 8],
  ['FB3', 'FLATBED_TRUCK', 'Flatbed truck 3', 290, 300, 12, 2.5, 3.5, 8],
  ['DT1', 'DUMP_TRUCK', 'Dump truck 1', 330, 300, 9, 2.6, 3.4, 8],
  ['DT2', 'DUMP_TRUCK', 'Dump truck 2', 350, 300, 9, 2.6, 3.4, 8],
  ['TK-T1', 'TANK_TRUCK', 'Tank truck 1', -2482 + 90, 538 - 45, 11, 2.6, 3.4, 6],
  ['TK-T2', 'TANK_TRUCK', 'Tank truck 2', -2912 + 90, 670 - 45, 11, 2.6, 3.4, 6],
  ['LB1', 'LOW_BED_TRAILER', 'Low-bed trailer 1', 370, 300, 16, 3, 3.4, 8],
  ['LB2', 'LOW_BED_TRAILER', 'Low-bed trailer 2', 395, 300, 16, 3, 3.4, 8],
  ['RT1', 'RORO_TRANSPORTER', 'RoRo transporter 1', 1100, 100, 18, 2.6, 4.2, 5],
  ['RT2', 'RORO_TRANSPORTER', 'RoRo transporter 2', 1100, 115, 18, 2.6, 4.2, 5],
  ['LLC1', 'LEVEL_LUFFING_CRANE', 'Level luffing crane 1', 700, 12, 24, 10, 40, 10],
  ['LLC2', 'LEVEL_LUFFING_CRANE', 'Level luffing crane 2', 760, 12, 24, 10, 40, 10],
  ['CC1', 'CRAWLER_CRANE', 'Crawler crane 1', 480, 80, 12, 8, 35, 10],
]
const reach = { LEVEL_LUFFING_CRANE: 40, CRAWLER_CRANE: 35 }
for (const [code, type, name, x, y, l, w, h, u] of parked) {
  const d = { ...mobile(u) }
  if (reach[type]) d.crane = { reachM: reach[type] }
  rect(code, type, name, x, y, l, w, h, { details: d })
}
rect('HGC1', 'HARBOUR_GANTRY_CRANE', 'Harbour gantry crane 1', 860, 12, 24, 20, 32, { details: { crane: { reachM: 24 } } })
rect('SU1', 'SHIP_UNLOADER', 'Ship unloader 1', 650, 12, 26, 18, 28, { details: { crane: { reachM: 30 } } })

// ---- Safety ----
line('FIRE-1', 'FIRE_ACCESS', 'Fire access route', [[-620, 275], [1020, 275]], 5, null)
polygon('DRN1', 'DRAINAGE_ZONE', 'Drainage zone 1', box(-300, 255, 120, 20), null)
polygon('DRN2', 'DRAINAGE_ZONE', 'Drainage zone 2', box(500, 255, 100, 20), null)
line('FENCE-1', 'FENCE', 'Perimeter fence', [[-640, 300], [1040, 300]], 0.3, 2.4)
rect('TH1', 'TRUCK_HOLDING', 'Truck holding area (main gate)', -90, 300, 70, 36, null,
  { details: { storage: { capacityValue: 20, capacityUnit: 'trucks', usableRatio: 1, designFill: 0.25, cargoTypeCode: null } } })
rect('TH2', 'TRUCK_HOLDING', 'Truck holding area (container gate)', 1120, 330, 70, 36, null,
  { details: { storage: { capacityValue: 20, capacityUnit: 'trucks', usableRatio: 1, designFill: 0.25, cargoTypeCode: null } } })

// ---- Links onto features, then checks ----
const byCode = new Map(features.map((f) => [f.id, f]))
for (const [from, to, kind] of links) {
  if (!byCode.has(to)) throw new Error('link to unknown asset ' + to)
  byCode.get(from).properties.links.push({ kind, to })
}
const counts = (k) => links.filter((l) => l[2] === k).length
if (features.length !== 301) throw new Error('expected 301 assets, got ' + features.length)
if (counts('SERVES') !== 82 || counts('FEEDS') !== 5) throw new Error('link counts ' + counts('SERVES') + '/' + counts('FEEDS'))
for (const f of features) {
  const coords = f.geometry.type === 'Polygon' ? f.geometry.coordinates[0] : f.geometry.coordinates
  for (const [lon, lat] of coords) {
    if (lon < extent.lonMin || lon > extent.lonMax || lat < extent.latMin || lat > extent.latMax) throw new Error(f.id + ' outside the extent')
  }
}

const doc = {
  type: 'FeatureCollection',
  castor: { schemaVersion: 1, port: 'westports', versionNo: 1, status: 'PUBLISHED', createdBy: 'seed', note: 'Westports demo layout' },
  features,
}
const out = new URL('../backend/castor-core/src/main/resources/seed/westports/layout-v1.geojson', import.meta.url)
writeFileSync(out, JSON.stringify(doc))
console.log(`wrote ${features.length} assets, ${links.length} links to ${out.pathname}`)
