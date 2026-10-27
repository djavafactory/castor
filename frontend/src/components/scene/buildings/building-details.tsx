import { Button } from '@/components/ui/button'
import { KeyValue, SectionHeading } from '@/components/ui/card'
import { useReferenceBuildings } from '@/lib/reference-api'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { buildingTitle, osmId } from './building-text'

/** Details of the clicked OSM building. Read-only: reference features can only be deselected. */
export function BuildingDetails() {
  const selected = useSelectionStore((s) => s.selectedReferenceId)
  const clear = useSelectionStore((s) => s.clear)
  const { data } = useReferenceBuildings(selected !== null)
  const building = selected && data?.find((f) => f.properties.osmRef === selected)
  if (!building) return null
  const p = building.properties
  const facts: [string, string][] = [
    ['Name', buildingTitle(p)],
    ['OpenStreetMap', p.osmRef],
  ]
  if (p.areaM2 != null) facts.push(['Footprint', `${Math.round(p.areaM2)} m2`])
  if (p.heightM != null) facts.push(['Height', `${p.heightM} m`])

  return (
    <section aria-label="Building details">
      <p className="text-xs text-muted">Reference feature, read-only (id {osmId(p.osmRef)})</p>
      <SectionHeading className="mt-3">Identity</SectionHeading>
      <KeyValue items={facts} />
      <SectionHeading>Data</SectionHeading>
      <KeyValue
        items={[
          ['Source', 'OpenStreetMap (ODbL)'],
          ['Accuracy', 'Approximate'],
        ]}
      />
      <Button size="sm" className="mt-4" onClick={clear}>
        Deselect
      </Button>
    </section>
  )
}
