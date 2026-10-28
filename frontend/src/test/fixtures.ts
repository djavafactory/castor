/** Valid API payloads for tests (MSW handlers). They match what the backend serves. */

export const API = 'http://api.test/api'

export const portFixture = {
  code: 'westports',
  name: 'Westports',
  city: 'Port Klang',
  timeZone: 'Asia/Kuala_Lumpur',
  utcOffset: '+08:00',
  origin: { lon: 101.3066304, lat: 2.9534057 },
  measureSrid: 3380,
  frameRotDeg: -120.299,
  extent: {
    type: 'Polygon',
    coordinates: [
      [
        [101.26, 2.9],
        [101.36, 2.9],
        [101.36, 3.02],
        [101.26, 3.02],
        [101.26, 2.9],
      ],
    ],
  },
  landExtent: null,
  quayLine: {
    type: 'LineString',
    coordinates: [
      [101.3134376, 2.9651179],
      [101.2798556, 2.9073376],
    ],
  },
  synthetic: true,
  publishedLayoutVersionId: null,
  berthCount: 0,
  attribution: '(c) OpenStreetMap contributors (ODbL)',
  dataSources: [
    { code: 'OSM', name: 'OpenStreetMap', licence: 'ODbL-1.0', mayTrace: false, mayPersist: true },
    {
      code: 'GOOGLE',
      name: 'Google Maps',
      licence: 'Google Maps Platform terms',
      mayTrace: false,
      mayPersist: false,
    },
  ],
}

export const controlPointsFixture = {
  type: 'FeatureCollection',
  features: [
    {
      type: 'Feature',
      id: 'main-gate',
      geometry: { type: 'Point', coordinates: [101.3093602, 2.9518844] },
      properties: {
        code: 'main-gate',
        name: 'Main gate',
        expected_local_x: -7.9,
        expected_local_y: 346.9,
        accuracy: 'APPROXIMATE',
        source: 'SYNTHETIC',
      },
    },
  ],
}

export const referenceFeaturesFixture = {
  type: 'FeatureCollection',
  features: [
    {
      type: 'Feature',
      id: 'way/1001',
      geometry: {
        type: 'Polygon',
        coordinates: [
          [
            [101.307, 2.953],
            [101.3074, 2.953],
            [101.3074, 2.9533],
            [101.307, 2.953],
          ],
        ],
      },
      properties: {
        kind: 'BUILDING',
        osm_ref: 'way/1001',
        name: 'Shed A',
        height_m: 12,
        width_m: null,
        area_m2: 1812.4,
        source: 'OSM',
        accuracy: 'APPROXIMATE',
      },
    },
  ],
}

const noSize = { lengthM: null, widthM: null, heightM: null, radiusM: null, lineWidthM: null }

export const assetTypesFixture = [
  {
    code: 'EXCAVATOR',
    label: 'Excavator',
    paletteGroup: 'HANDLING',
    category: 'MOBILE',
    geometryKind: 'POINT',
    placementMode: 'PLACE',
    flags: {
      mobile: true,
      solid: false,
      flat: false,
      zone: false,
      mayExtendOverWater: false,
      mustBeOnBerth: false,
    },
    defaults: { ...noSize, lengthM: 10, widthM: 3.2, heightM: 3.4 },
    min: { ...noSize, lengthM: 0.5, widthM: 0.5, heightM: 0.1, radiusM: 1 },
    max: noSize,
    defaultCapacityValue: null,
    defaultCapacityUnit: null,
    params: {},
    modelKey: 'excavator',
    modelRev: 1,
    colourHex: '#E8A317',
    hint: 'Click the map to place an excavator.',
    sortOrder: 13,
  },
]

/** A warehouse type as GET /api/v1/asset-types sends it (the Inspector reads its sizes and its layer). */
export const warehouseTypeFixture = {
  code: 'WAREHOUSE',
  label: 'Warehouse',
  paletteGroup: 'STORAGE',
  category: 'STORAGE',
  geometryKind: 'RECT',
  placementMode: 'PLACE',
  flags: {
    mobile: false,
    solid: true,
    flat: false,
    zone: false,
    mayExtendOverWater: false,
    mustBeOnBerth: false,
  },
  defaults: { ...noSize, lengthM: 80, widthM: 40, heightM: 12 },
  min: { ...noSize, lengthM: 10, widthM: 10, heightM: 3 },
  max: noSize,
  defaultCapacityValue: null,
  defaultCapacityUnit: null,
  params: {},
  modelKey: 'warehouse',
  modelRev: 1,
  colourHex: '#C9BFA3',
  hint: 'Click the map to place a warehouse.',
  sortOrder: 1,
}

export const catalogueFixture = {
  cargoTypes: [
    {
      code: 'COAL',
      name: 'Thermal coal',
      familyCode: 'DRY_BULK',
      baseUnit: 't',
      densityTM3: 0.85,
      storageForm: 'PILE',
      maxStackM: null,
      reposeDeg: 37,
      colourHex: '#2B2B30',
    },
  ],
  shipClasses: [
    {
      code: 'SUPRAMAX',
      name: 'Supramax',
      kind: 'BULK',
      loaM: 199,
      beamM: 32.2,
      draftM: 12.8,
      dwtT: 58000,
    },
  ],
  cargoFamilies: [{ code: 'DRY_BULK', label: 'Dry bulk' }],
  surfaces: [{ code: 'CONCRETE', label: 'Concrete', colourHex: '#D9D3C6' }],
  accuracyClasses: [
    { code: 'APPROXIMATE', label: 'Approximate', colourHex: '#8A8F98', sortOrder: 4 },
  ],
  assetCategories: [{ code: 'MOBILE', label: 'Mobile equipment', sortOrder: 9 }],
  paletteGroups: [{ code: 'HANDLING', label: 'Material handling', sortOrder: 2 }],
  densityNote: 'Densities are typical values, not a standard.',
}

export const layoutFixture = {
  versionId: 'v-1',
  number: 1,
  status: 'DRAFT',
  assets: [
    { id: 'a-1', typeCode: 'EXCAVATOR', rowVersion: 3, name: 'Excavator 1' },
    { id: 'a-2', typeCode: 'EXCAVATOR', rowVersion: 1, name: 'Excavator 2' },
  ],
}

/** The same layout as the server sends it: {layout, features} with GeoJSON features. */
export function layoutWire(
  layout: {
    versionId: string
    number: number
    status: string
    assets: Record<string, unknown>[]
  } = layoutFixture,
  header: Record<string, unknown> = {},
) {
  return {
    layout: {
      id: layout.versionId,
      versionNo: layout.number,
      status: layout.status,
      basedOnVersionNo: 1,
      createdBy: 'ada',
      createdAt: '2026-10-27T01:00:00Z',
      updatedBy: 'grace',
      updatedAt: '2026-10-27T02:00:00Z',
      ...header,
    },
    features: {
      type: 'FeatureCollection',
      features: layout.assets.map(({ id, typeCode, ...properties }) => ({
        type: 'Feature',
        id,
        geometry: { type: 'Polygon', coordinates: [] },
        properties: { ...properties, type: typeCode },
      })),
    },
  }
}

const ORIGIN = { lon: 101.3066304, lat: 2.9534057 }

/** A feature of GET /api/v1/ports/{code}/layouts/current, with the members the app reads. */
export function layoutFeature(
  id: string,
  type: string,
  properties: Record<string, unknown> = {},
  geometry?: { type: string; coordinates: unknown },
) {
  const { dLon = 0, ...rest } = properties as { dLon?: number } & Record<string, unknown>
  const lon = ORIGIN.lon + dLon
  const lat = ORIGIN.lat
  return {
    type: 'Feature' as const,
    id,
    geometry: geometry ?? { type: 'Point', coordinates: [lon, lat] },
    properties: {
      code: id.toUpperCase(),
      type,
      name: `${type} ${id}`,
      anchor: [lon, lat],
      headingDeg: null,
      lengthM: null,
      widthM: null,
      heightM: null,
      radiusM: null,
      lineWidthM: null,
      elevationM: null,
      storageZoneCode: null,
      details: {},
      links: [],
      provenance: { source: 'SYNTHETIC', accuracy: 'APPROXIMATE', capturedOn: null, note: null },
      override: null,
      computed: {},
      rowVersion: 0,
      ...rest,
    },
  }
}

function square(lon: number, lat: number, half = 0.0002) {
  return {
    type: 'Polygon',
    coordinates: [
      [
        [lon - half, lat - half],
        [lon + half, lat - half],
        [lon + half, lat + half],
        [lon - half, lat + half],
        [lon - half, lat - half],
      ],
    ],
  }
}

export const currentLayoutFixture = {
  layout: {
    id: '5a1c0000-0000-4000-8000-000000000003',
    versionNo: 3,
    status: 'PUBLISHED',
    publishedAt: '2026-10-27T06:05:00Z',
    publishedBy: 'pia',
    note: 'Berth 4 extended',
  },
  features: {
    type: 'FeatureCollection' as const,
    features: [
      layoutFeature(
        'wh1',
        'WAREHOUSE',
        {
          dLon: 0.0004,
          name: 'Warehouse 1',
          lengthM: 60,
          widthM: 30,
          heightM: 12,
          headingDeg: 45,
          details: { warehouse: { roofRiseM: 2, dockDoors: 4 } },
          computed: { areaM2: 1800 },
        },
        square(ORIGIN.lon + 0.0004, ORIGIN.lat),
      ),
      layoutFeature('cy1', 'CONTAINER_YARD', {
        dLon: 0.0008,
        name: 'Yard 1',
        lengthM: 120,
        widthM: 60,
        details: { containerYard: { maxTiers: 4, designFill: 0.5, capacityTeu: 900 } },
      }),
      layoutFeature('sts1', 'STS_CRANE', {
        dLon: 0.0012,
        name: 'STS 1',
        lengthM: 16,
        widthM: 22,
        heightM: 55,
      }),
      layoutFeature('fk1', 'FORKLIFT', { dLon: 0.0016, name: 'Forklift 1' }),
      layoutFeature('x1', 'NOT_A_MODEL', { dLon: 0.002, name: 'Mystery' }),
      layoutFeature(
        'rd1',
        'INTERNAL_ROAD',
        { dLon: 0.0006, lineWidthM: 8 },
        {
          type: 'LineString',
          coordinates: [
            [ORIGIN.lon, ORIGIN.lat + 0.0003],
            [ORIGIN.lon + 0.0006, ORIGIN.lat + 0.0003],
            [ORIGIN.lon + 0.0006, ORIGIN.lat + 0.0006],
          ],
        },
      ),
    ],
  },
}
