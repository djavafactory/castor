import { buildingHoverText, buildingTitle, osmId } from './building-text'

const base = {
  kind: 'BUILDING' as const,
  osmRef: 'way/1300405460',
  name: null,
  heightM: 12,
  areaM2: 1812.4,
  source: 'OSM',
  accuracy: 'APPROXIMATE' as const,
}

describe('building text', () => {
  it('takes the OpenStreetMap number from the reference', () => {
    expect(osmId('way/1300405460')).toBe('1300405460')
    expect(osmId('42')).toBe('42')
  })

  it('shows the osm id when the building has no name, the name when it has one', () => {
    expect(buildingTitle(base)).toBe('Building 1300405460')
    expect(buildingTitle({ ...base, name: 'Warehouse 7' })).toBe('Building Warehouse 7')
  })

  it('writes the hover text: name or id, area, height, source', () => {
    expect(buildingHoverText(base)).toBe('Building 1300405460, 1812 m2, 12 m (OpenStreetMap)')
    expect(buildingHoverText({ ...base, name: 'Warehouse 7', heightM: 9.5 })).toBe(
      'Building Warehouse 7, 1812 m2, 9.5 m (OpenStreetMap)',
    )
  })

  it('leaves out a figure that is not known', () => {
    expect(buildingHoverText({ ...base, areaM2: null, heightM: null })).toBe(
      'Building 1300405460 (OpenStreetMap)',
    )
  })
})
