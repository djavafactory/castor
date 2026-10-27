import { useCurrentLayout } from '@/lib/api'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { ACCURACY, detailRows, humanKey } from './asset-rows'

function Row({ label, value }: { label: string; value: string | number | null | undefined }) {
  if (value === null || value === undefined) return null
  return (
    <>
      <dt className="text-muted">{label}</dt>
      <dd className="break-words">{value}</dd>
    </>
  )
}

const metres = (v: number | null | undefined) => (v == null ? null : `${v} m`)

/** Details of the selected asset of the published layout. Read-only: there is no edit control on this panel. */
export function LayoutAssetDetails() {
  const selected = useSelectionStore((s) => s.selectedAssetId)
  const { data } = useCurrentLayout()
  const feature = selected && data?.data.features.features.find((f) => f.id === selected)
  if (!feature) return null
  const p = feature.properties
  const computed = p.computed

  return (
    <section aria-label="Asset details">
      <h3 className="text-sm font-semibold">{p.name || p.code}</h3>
      <p className="mt-0.5 text-xs text-muted">
        {humanKey(p.type.toLowerCase())} · published layout, read-only
      </p>
      <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-[13px]">
        <Row label="Code" value={p.code} />
        <Row label="Length" value={metres(p.lengthM)} />
        <Row label="Width" value={metres(p.widthM)} />
        <Row label="Height" value={metres(p.heightM)} />
        <Row label="Radius" value={metres(p.radiusM)} />
        <Row label="Heading" value={p.headingDeg == null ? null : `${p.headingDeg}°`} />
        <Row label="Elevation" value={metres(p.elevationM)} />
        <Row
          label="Area"
          value={computed.areaM2 == null ? null : `${Math.round(computed.areaM2)} m2`}
        />
        <Row
          label="Length (path)"
          value={computed.lengthM == null ? null : `${Math.round(computed.lengthM)} m`}
        />
        <Row label="Storage zone" value={p.storageZoneCode} />
        {detailRows(p.details).map(([label, value]) => (
          <Row key={label} label={label} value={value} />
        ))}
        <Row label="Source" value={p.provenance.source} />
        <Row label="Accuracy" value={ACCURACY[p.provenance.accuracy] ?? p.provenance.accuracy} />
        <Row label="Captured" value={p.provenance.capturedOn} />
        <Row label="Note" value={p.provenance.note} />
      </dl>
    </section>
  )
}
