import { useReferenceBuildings } from '@/lib/reference-api'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { buildingTitle } from './building-text'

const ACCURACY: Record<string, string> = {
  SURVEYED: 'Surveyed',
  VERIFIED: 'Verified',
  OPERATOR: 'Operator',
  APPROXIMATE: 'Approximate',
}

/** Details of the clicked OSM building. Read-only: reference features have no editing controls anywhere. */
export function BuildingDetails() {
  const selected = useSelectionStore((s) => s.selectedReferenceId)
  const { data } = useReferenceBuildings(selected !== null)
  const building = selected && data?.find((f) => f.properties.osmRef === selected)
  if (!building) return null
  const p = building.properties

  return (
    <section aria-label="Building details">
      <h3 className="text-sm font-semibold">{buildingTitle(p)}</h3>
      <p className="mt-0.5 text-xs text-muted">Reference feature, read-only</p>
      <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-[13px]">
        <dt className="text-muted">OpenStreetMap</dt>
        <dd>{p.osmRef}</dd>
        {p.areaM2 != null && (
          <>
            <dt className="text-muted">Footprint</dt>
            <dd>{Math.round(p.areaM2)} m2</dd>
          </>
        )}
        {p.heightM != null && (
          <>
            <dt className="text-muted">Height</dt>
            <dd>{p.heightM} m</dd>
          </>
        )}
        <dt className="text-muted">Source</dt>
        <dd>OpenStreetMap (ODbL)</dd>
        <dt className="text-muted">Accuracy</dt>
        <dd>{ACCURACY[p.accuracy] ?? p.accuracy}</dd>
      </dl>
    </section>
  )
}
