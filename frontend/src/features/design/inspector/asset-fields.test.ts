import { describe, expect, it } from 'vitest'
import { dependentsOf, type AssetFields } from './asset-fields'

const asset = (id: string, name: string, links?: { kind: string; toAssetId: string }[]) =>
  ({ id, name, links }) as unknown as AssetFields

describe('dependentsOf', () => {
  it('names the links of the asset and the links other assets have to it', () => {
    const berth = asset('b', 'Berth 1', [])
    const crane = asset('c', 'Crane 1', [{ kind: 'SERVES', toAssetId: 'b' }])
    expect(dependentsOf(berth, [berth, crane])).toHaveLength(1)
    expect(dependentsOf(crane, [berth, crane])).toHaveLength(1)
  })

  it('copes with an asset that is still being saved and has no links yet', () => {
    // The Inspector renders the asset a drawn shape had while its POST is in flight
    const saving = asset('pending-1', 'Warehouse 1')
    const berth = asset('b', 'Berth 1', [])
    expect(dependentsOf(saving, [saving, berth])).toEqual([])
    expect(dependentsOf(berth, [saving, berth])).toEqual([])
  })
})
