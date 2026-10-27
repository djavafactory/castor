import { useUiStore } from '@/lib/stores/ui-store'
import {
  CONTAINERS_LAYER,
  layerLocked,
  layerOf,
  layerShown,
  layers,
  visibleFeatures,
} from './layers'

describe('layers', () => {
  it('lists the rows of All layers in order', () => {
    expect(layers().map((l) => l.label)).toEqual([
      'Terminals',
      'Surface areas',
      'Roads',
      'Storage pads',
      'Buildings and warehouses',
      'Buildings (OSM)',
      'Roads (OSM)',
      'Berths',
      'Quay cranes',
      'Marine / berth',
      'Land / safety',
      'Storage',
      'Dry bulk',
      'Liquid bulk',
      'RoRo',
      'Container terminal',
      'Transport',
      'Mobile equipment',
    ])
  })

  it('puts an asset on the layer of its type, else of its category', () => {
    expect(layerOf('TERMINAL_BOUNDARY', 'ZONE')).toBe('terminals')
    expect(layerOf('STORAGE_PAD', 'DRY_BULK')).toBe('storage-pads')
    expect(layerOf('WAREHOUSE', 'STORAGE')).toBe('buildings')
    expect(layerOf('INTERNAL_ROAD', 'TRANSPORT')).toBe('roads')
    expect(layerOf('BERTH', 'MARINE')).toBe('berths')
    expect(layerOf('QUAY_CRANE_RAIL', 'LIFTING')).toBe('quay-cranes')
    expect(layerOf('BOLLARD', 'MARINE')).toBe('marine')
    expect(layerOf('FENCE', 'SAFETY')).toBe('safety')
    expect(layerOf('SILO', 'DRY_BULK')).toBe('dry-bulk')
    expect(layerOf('LIQUID_TANK', 'LIQUID_BULK')).toBe('liquid-bulk')
    expect(layerOf('PARKING_YARD', 'RORO')).toBe('roro')
    expect(layerOf('RTG', 'CONTAINER')).toBe(CONTAINERS_LAYER)
    expect(layerOf('GATE', 'TRANSPORT')).toBe('transport')
    expect(layerOf('FORKLIFT', 'MOBILE')).toBe('mobile')
    expect(layerOf('SHED', 'STORAGE')).toBe('storage')
  })

  it('leaves a type without a known category on no layer', () => {
    expect(layerOf('MYSTERY', undefined)).toBeUndefined()
    expect(layerOf('MYSTERY', 'OTHER')).toBeUndefined()
  })

  it('shows a layer unless it is off or another one is isolated', () => {
    expect(layerShown({ layerVisibility: {}, isolatedLayer: null }, 'roads')).toBe(true)
    expect(layerShown({ layerVisibility: { roads: false }, isolatedLayer: null }, 'roads')).toBe(
      false,
    )
    expect(layerShown({ layerVisibility: {}, isolatedLayer: 'marine' }, 'roads')).toBe(false)
    expect(layerShown({ layerVisibility: {}, isolatedLayer: 'marine' }, 'marine')).toBe(true)
  })

  it('knows a locked layer', () => {
    useUiStore.setState({ lockedLayers: { berths: true } })
    expect(layerLocked('BERTH', 'MARINE')).toBe(true)
    expect(layerLocked('BOLLARD', 'MARINE')).toBe(false)
    useUiStore.setState({ lockedLayers: {} })
  })

  describe('visibleFeatures', () => {
    const types = [
      { code: 'BERTH', category: 'MARINE' },
      { code: 'BOLLARD', category: 'MARINE' },
      { code: 'FORKLIFT', category: 'MOBILE' },
    ]
    const features = ['BERTH', 'BOLLARD', 'FORKLIFT', 'MYSTERY'].map((type) => ({
      properties: { type },
    }))
    const typesOf = (state: Parameters<typeof visibleFeatures>[2]) =>
      visibleFeatures(features, types, state).map((f) => f.properties.type)

    it('shows everything by default, and what has no layer always', () => {
      expect(typesOf({ layerVisibility: {}, isolatedLayer: null })).toEqual([
        'BERTH',
        'BOLLARD',
        'FORKLIFT',
        'MYSTERY',
      ])
      expect(
        typesOf({ layerVisibility: { berths: false, mobile: false }, isolatedLayer: null }),
      ).toEqual(['BOLLARD', 'MYSTERY'])
    })

    it('isolates one layer; everything else without a layer stays', () => {
      expect(typesOf({ layerVisibility: {}, isolatedLayer: 'berths' })).toEqual([
        'BERTH',
        'MYSTERY',
      ])
    })

    it('shows everything while the asset types have not loaded', () => {
      expect(
        visibleFeatures(features, undefined, {
          layerVisibility: { marine: false },
          isolatedLayer: null,
        }),
      ).toHaveLength(4)
    })
  })
})
