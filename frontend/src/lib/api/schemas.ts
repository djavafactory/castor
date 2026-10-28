import { z } from 'zod'

/** zod schemas of the API responses. A response that does not match is an error state, never rendered. */

const position = z.tuple([z.number(), z.number()]).rest(z.number())

export const polygonSchema = z.object({
  type: z.literal('Polygon'),
  coordinates: z.array(z.array(position)),
})
export const lineStringSchema = z.object({
  type: z.literal('LineString'),
  coordinates: z.array(position),
})
export const pointSchema = z.object({ type: z.literal('Point'), coordinates: position })
/** Any GeoJSON geometry (background shapes mix polygons, lines and points). */
export const geometrySchema = z.looseObject({ type: z.string(), coordinates: z.unknown() })

function featureCollection<G extends z.ZodType, P extends z.ZodType>(geometry: G, properties: P) {
  return z.object({
    type: z.literal('FeatureCollection'),
    features: z.array(z.looseObject({ type: z.literal('Feature'), geometry, properties })),
  })
}

// ---- Port ----

export const portSummarySchema = z.object({ code: z.string(), name: z.string() })
export const portListSchema = z.array(portSummarySchema)

export const dataSourceSchema = z.object({
  code: z.string(),
  name: z.string(),
  licence: z.string(),
  mayTrace: z.boolean(),
  mayPersist: z.boolean(),
})

export const portSchema = z.object({
  code: z.string(),
  name: z.string(),
  city: z.string(),
  timeZone: z.string(),
  /** Derived from the time zone by the server, for example +08:00. */
  utcOffset: z.string().regex(/^[+-]\d{2}:\d{2}$/),
  origin: z.object({ lon: z.number(), lat: z.number() }),
  measureSrid: z.number().int(),
  frameRotDeg: z.number(),
  extent: polygonSchema,
  landExtent: polygonSchema.nullable(),
  quayLine: lineStringSchema.nullable(),
  synthetic: z.boolean(),
  publishedLayoutVersionId: z.string().nullable(),
  berthCount: z.number().int().nonnegative(),
  attribution: z.string(),
  dataSources: z.array(dataSourceSchema),
})

export const accuracyCodeSchema = z.enum(['SURVEYED', 'VERIFIED', 'OPERATOR', 'APPROXIMATE'])

export const controlPointsSchema = featureCollection(
  pointSchema,
  z.object({
    code: z.string(),
    name: z.string(),
    expected_local_x: z.number(),
    expected_local_y: z.number(),
    accuracy: accuracyCodeSchema,
    source: z.string(),
  }),
)

export const referenceKindSchema = z.enum([
  'BUILDING',
  'ROAD',
  'RAIL',
  'COASTLINE',
  'JETTY',
  'WATER',
  'LANDUSE',
])

export const referenceFeaturesSchema = featureCollection(
  geometrySchema,
  z.object({
    kind: referenceKindSchema,
    osm_ref: z.string(),
    name: z.string().nullable(),
    height_m: z.number().nullable(),
    width_m: z.number().nullable(),
    /** Footprint area in the port measurement CRS; null for lines. */
    area_m2: z.number().nullable(),
    source: z.string(),
    accuracy: accuracyCodeSchema,
  }),
)

// ---- Asset types ----

const sizesSchema = z.object({
  lengthM: z.number().nullable(),
  widthM: z.number().nullable(),
  heightM: z.number().nullable(),
  radiusM: z.number().nullable(),
  lineWidthM: z.number().nullable(),
})

export const geometryKindSchema = z.enum([
  'POINT',
  'RECT',
  'CIRCLE',
  'LINE',
  'POLYGON',
  'QUAY_SEGMENT',
])

export const assetTypeSchema = z.object({
  code: z.string(),
  label: z.string(),
  paletteGroup: z.string(),
  category: z.string(),
  geometryKind: geometryKindSchema,
  placementMode: z.enum(['PLACE', 'DRAW']),
  flags: z.object({
    mobile: z.boolean(),
    solid: z.boolean(),
    flat: z.boolean(),
    zone: z.boolean(),
    mayExtendOverWater: z.boolean(),
    mustBeOnBerth: z.boolean(),
  }),
  defaults: sizesSchema,
  min: sizesSchema,
  max: sizesSchema,
  defaultCapacityValue: z.number().nullable(),
  defaultCapacityUnit: z.string().nullable(),
  /** Type specific defaults without a column: reach, outreach, tiers, rate ... */
  params: z.record(z.string(), z.unknown()),
  modelKey: z.string(),
  modelRev: z.number().int().min(1),
  colourHex: z.string().regex(/^#[0-9A-Fa-f]{6}$/),
  hint: z.string(),
  sortOrder: z.number().int(),
})
export const assetTypesSchema = z.array(assetTypeSchema)

// ---- Catalogue ----

export const catalogueSchema = z.object({
  cargoTypes: z.array(
    z.object({
      code: z.string(),
      name: z.string(),
      familyCode: z.string(),
      baseUnit: z.enum(['t', 'm3', 'pcs', 'veh', 'TEU']),
      densityTM3: z.number(),
      storageForm: z.enum(['PILE', 'STACK', 'SILO', 'TANK', 'UNIT', 'PARKING']),
      maxStackM: z.number().nullable(),
      reposeDeg: z.number().nullable(),
      colourHex: z.string(),
    }),
  ),
  shipClasses: z.array(
    z.object({
      code: z.string(),
      name: z.string(),
      kind: z.enum(['GENERAL_CARGO', 'BULK', 'TANKER', 'RORO', 'CONTAINER']),
      loaM: z.number(),
      beamM: z.number(),
      draftM: z.number(),
      dwtT: z.number().int(),
    }),
  ),
  cargoFamilies: z.array(z.object({ code: z.string(), label: z.string() })),
  surfaces: z.array(z.object({ code: z.string(), label: z.string(), colourHex: z.string() })),
  accuracyClasses: z.array(
    z.object({
      code: accuracyCodeSchema,
      label: z.string(),
      colourHex: z.string(),
      sortOrder: z.number().int(),
    }),
  ),
  assetCategories: z.array(
    z.object({ code: z.string(), label: z.string(), sortOrder: z.number().int() }),
  ),
  paletteGroups: z.array(
    z.object({ code: z.string(), label: z.string(), sortOrder: z.number().int() }),
  ),
  /** Help text shown with the catalogue. */
  densityNote: z.string(),
})

// ---- Layout ----

export const layoutAssetSchema = z.looseObject({
  id: z.string(),
  typeCode: z.string(),
  rowVersion: z.number().int(),
})

export const layoutStatusSchema = z.enum(['DRAFT', 'PUBLISHED', 'SUPERSEDED', 'DISCARDED'])

/** The layout as the screens use it: the version header with one flat object per asset. */
export const layoutSchema = z.looseObject({
  versionId: z.string(),
  number: z.number().int(),
  status: layoutStatusSchema,
  /** Number of the version this one was copied from; null for a blank draft. */
  basedOnVersionNo: z.number().int().nullable().optional(),
  createdBy: z.string().optional(),
  createdAt: z.string().optional(),
  /** Who last changed an asset, and when. */
  updatedBy: z.string().optional(),
  updatedAt: z.string().optional(),
  assets: z.array(layoutAssetSchema),
})

const optionalMetres = z.number().nullish()

/** Details groups are present only when the asset type has them; members the app does not read are kept. */
export const layoutDetailsSchema = z.looseObject({
  containerYard: z
    .looseObject({
      maxTiers: z.number().int(),
      designFill: z.number().nullish(),
      capacityTeu: z.number().int().nullish(),
    })
    .optional(),
  storagePad: z
    .looseObject({ bays: z.number().int(), rows: z.number().int(), maxHeightM: optionalMetres })
    .optional(),
  storage: z
    .looseObject({ designFill: z.number().nullish(), cargoTypeCode: z.string().nullish() })
    .optional(),
  warehouse: z.looseObject({ roofRiseM: optionalMetres, dockDoors: z.number().int() }).optional(),
  berth: z.looseObject({ depthM: optionalMetres }).optional(),
  mobile: z.looseObject({ positionMode: z.string(), uncertaintyM: optionalMetres }).optional(),
})

export const layoutFeaturePropertiesSchema = z.looseObject({
  code: z.string(),
  type: z.string(),
  name: z.string(),
  /** [lon, lat] */
  anchor: z.tuple([z.number(), z.number()]),
  headingDeg: optionalMetres,
  lengthM: optionalMetres,
  widthM: optionalMetres,
  heightM: optionalMetres,
  radiusM: optionalMetres,
  lineWidthM: optionalMetres,
  elevationM: optionalMetres,
  storageZoneCode: z.string().nullish(),
  details: layoutDetailsSchema,
  provenance: z.looseObject({
    source: z.string(),
    accuracy: z.string(),
    capturedOn: z.string().nullish(),
    note: z.string().nullish(),
  }),
  computed: z.looseObject({
    areaM2: optionalMetres,
    perimeterM: optionalMetres,
    lengthM: optionalMetres,
  }),
  rowVersion: z.number().int(),
})

/** What the server sends for a version: {layout, features} with GeoJSON features whose id is the asset id. */
const layoutHeaderSchema = z.looseObject({
  id: z.string(),
  versionNo: z.number().int(),
  status: layoutStatusSchema,
  basedOnVersionNo: z.number().int().nullable().optional(),
  createdBy: z.string().optional(),
  createdAt: z.string().optional(),
  updatedBy: z.string().optional(),
  updatedAt: z.string().optional(),
  publishedAt: z.string().nullish(),
  publishedBy: z.string().nullish(),
  note: z.string().nullish(),
})

/** A feature of the published layout: the monitor reads every property, so all of them are typed. */
export const layoutFeatureSchema = z.looseObject({
  id: z.string(),
  geometry: geometrySchema,
  properties: layoutFeaturePropertiesSchema,
  type: z.literal('Feature'),
})

/** A feature of a draft: the editor only needs the type and the row version to track it. */
const draftFeatureSchema = z.looseObject({
  id: z.string(),
  geometry: geometrySchema,
  properties: z.looseObject({ type: z.string(), rowVersion: z.number().int() }),
  type: z.literal('Feature'),
})

export const currentLayoutSchema = z.object({
  layout: layoutHeaderSchema,
  features: z.object({
    type: z.literal('FeatureCollection'),
    features: z.array(layoutFeatureSchema),
  }),
})

export const layoutDocumentSchema = z
  .object({
    layout: layoutHeaderSchema,
    features: z.object({
      type: z.literal('FeatureCollection'),
      features: z.array(draftFeatureSchema),
    }),
  })
  .transform(({ layout, features }): Layout => {
    const { id, versionNo, ...header } = layout
    return {
      ...header,
      versionId: id,
      number: versionNo,
      assets: features.features.map((f) => ({
        ...f.properties,
        id: f.id,
        typeCode: f.properties.type,
        rowVersion: f.properties.rowVersion,
        geometry: f.geometry,
      })),
    }
  })

/** One failed rule of the catalogue (README section 6); the first asset id is the one to select. */
export const validationIssueSchema = z.object({
  code: z.string(),
  level: z.enum(['ERROR', 'WARNING']),
  message: z.string(),
  assetIds: z.array(z.string()),
})

/** POST /v1/layouts/{id}/validate: errors block publishing, warnings do not. */
export const validationSchema = z.object({
  errors: z.number().int().nonnegative(),
  warnings: z.number().int().nonnegative(),
  issues: z.array(validationIssueSchema),
})

/** One row of GET /v1/ports/{code}/layouts, newest first. */
export const layoutVersionSchema = z.object({
  id: z.string(),
  versionNo: z.number().int(),
  status: layoutStatusSchema,
  createdBy: z.string(),
  createdAt: z.string(),
  publishedBy: z.string().nullish(),
  publishedAt: z.string().nullish(),
  /** Who reviewed the draft before it was published. */
  reviewedBy: z.string().nullish(),
  /** Number of the version this one was restored from; null when it was not a restore. */
  restoredFromVersionNo: z.number().int().nullish(),
  note: z.string().nullish(),
  assetCount: z.number().int().nonnegative(),
})

export const layoutVersionsSchema = z.array(layoutVersionSchema)

/** One asset in one version: what it was called and how big its shape was. */
export const assetHistorySchema = z.array(
  z.object({
    versionId: z.string(),
    versionNo: z.number().int(),
    status: layoutStatusSchema,
    name: z.string(),
    geometry: z.string(),
    changedBy: z.string(),
    changedAt: z.string(),
  }),
)

const diffItemSchema = z.object({
  assetId: z.string(),
  code: z.string(),
  name: z.string(),
  type: z.string(),
})

/** GET /v1/layouts/{id}/diff?against=: seen from the version asked about. */
export const layoutDiffSchema = z.object({
  added: z.array(diffItemSchema),
  removed: z.array(diffItemSchema),
  changed: z.array(
    z.object({
      assetId: z.string(),
      code: z.string(),
      name: z.string(),
      /** Names of the properties that differ, for example geometry or heightM. */
      fields: z.array(z.string()),
    }),
  ),
})

export const publishResultSchema = z.object({
  versionNo: z.number().int(),
  publishedAt: z.string(),
  publishedBy: z.string(),
  reviewedBy: z.string().nullish(),
  assetCount: z.number().int(),
})

/** Publishers and admins other than the caller: who may be named as reviewer. */
export const reviewersSchema = z.array(z.object({ username: z.string(), displayName: z.string() }))

export const accuracyReportSchema = z.object({
  total: z.number().int(),
  byAccuracy: z.record(z.string(), z.number().int()),
})

export const placeKindSchema = z.enum(['PORT', 'CONTROL_POINT', 'ASSET', 'REFERENCE'])

/** A place of the port's own data: the port, a control point, an asset or an OpenStreetMap shape. */
export const searchResultsSchema = z.array(
  z.object({
    kind: placeKindSchema,
    /** Port code, control point code, asset id or OpenStreetMap reference, by kind. */
    id: z.string(),
    label: z.string(),
    /** The type of the thing, for example "Berth". */
    sublabel: z.string(),
    lon: z.number(),
    lat: z.number(),
    /** minLon, minLat, maxLon, maxLat of the shape; a point has a box of no size. */
    bbox: z.tuple([z.number(), z.number(), z.number(), z.number()]),
  }),
)

export type Port = z.infer<typeof portSchema>
export type PortSummary = z.infer<typeof portSummarySchema>
export type DataSource = z.infer<typeof dataSourceSchema>
export type ControlPoints = z.infer<typeof controlPointsSchema>
export type ReferenceKind = z.infer<typeof referenceKindSchema>
export type ReferenceFeatures = z.infer<typeof referenceFeaturesSchema>
export type AssetType = z.infer<typeof assetTypeSchema>
export type Catalogue = z.infer<typeof catalogueSchema>
export type Layout = z.infer<typeof layoutSchema>
export type LayoutDiff = z.infer<typeof layoutDiffSchema>
export type PublishResult = z.infer<typeof publishResultSchema>
export type Reviewer = z.infer<typeof reviewersSchema>[number]
export type CurrentLayout = z.infer<typeof currentLayoutSchema>
export type LayoutHeader = z.infer<typeof layoutHeaderSchema>
export type LayoutFeature = z.infer<typeof layoutFeatureSchema>
export type LayoutFeatureProperties = z.infer<typeof layoutFeaturePropertiesSchema>
export type LayoutAsset = z.infer<typeof layoutAssetSchema>
export type ValidationIssue = z.infer<typeof validationIssueSchema>
export type ValidationReport = z.infer<typeof validationSchema>
export type LayoutVersion = z.infer<typeof layoutVersionsSchema>[number]
export type AssetHistoryEntry = z.infer<typeof assetHistorySchema>[number]
export type AccuracyReport = z.infer<typeof accuracyReportSchema>
export type SearchResult = z.infer<typeof searchResultsSchema>[number]
