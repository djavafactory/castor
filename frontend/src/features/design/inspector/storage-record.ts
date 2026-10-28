import { detailChange } from './field-values'
import type { InspectorContext } from './inspector-context'

/** The storage record of an asset; created with the type's default capacity when the asset has none yet. */
export function storageChange(ctx: InspectorContext, members: Record<string, unknown>) {
  const { asset, type } = ctx
  if (asset.details.storage) return detailChange(asset, 'storage', members)
  return detailChange(asset, 'storage', {
    capacityValue: type.defaultCapacityValue ?? 0,
    capacityUnit: type.defaultCapacityUnit ?? 't',
    usableRatio: 1,
    designFill: 0,
    cargoTypeCode: null,
    allowedCargoTypes: [],
    maxFloorLoadTM2: null,
    maxStackM: null,
    bearingCapacityKpa: null,
    weatherExposed: true,
    ...members,
  })
}
