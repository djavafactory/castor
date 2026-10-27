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
