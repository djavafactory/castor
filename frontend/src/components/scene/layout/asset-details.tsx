import { Button } from '@/components/ui/button'
import { KeyValue, ProgressBar, SectionHeading } from '@/components/ui/card'
import { useAssetTypes, useCatalogue, useCurrentLayout } from '@/lib/api'
import { useSelectionStore } from '@/lib/stores/selection-store'
import {
  ACCURACY,
  capacityOf,
  coord,
  detailRows,
  headingText,
  humanKey,
  sizeText,
} from './asset-rows'

/** Details of the selected asset of the published layout. The only control is Deselect. */
export function LayoutAssetDetails() {
  const selected = useSelectionStore((s) => s.selectedAssetId)
  const clear = useSelectionStore((s) => s.clear)
  const { data } = useCurrentLayout()
  const types = useAssetTypes()
  const catalogue = useCatalogue()
  const feature = selected && data?.data.features.features.find((f) => f.id === selected)
  if (!feature) return null
  const p = feature.properties
  const [lon, lat] = p.anchor
  const typeLabel =
    types.data?.find((t) => t.code === p.type)?.label ?? humanKey(p.type.toLowerCase())
  const accuracy = catalogue.data?.accuracyClasses.find((a) => a.code === p.provenance.accuracy)
  const accuracyLabel = accuracy?.label ?? ACCURACY[p.provenance.accuracy] ?? p.provenance.accuracy
  const geometryType = (feature.geometry as { type?: string }).type
  const size = sizeText(p, geometryType)
  const heading = headingText(p)
  const capacity = capacityOf(p)
  const details = detailRows(p.details)

  const position: [string, string][] = [
    ['Longitude', coord(lon)],
    ['Latitude', coord(lat)],
  ]
  if (heading) position.push(['Heading', heading])
  if (size) position.push(['Size', size])

  return (
    <section aria-label="Asset details">
      <SectionHeading className="mt-0">Identity</SectionHeading>
      <KeyValue
        items={[
          ['Name', p.name || p.code],
          ['Code', p.code],
          ['Type', typeLabel],
        ]}
      />
      <SectionHeading>Position</SectionHeading>
      <KeyValue items={position} />
      {capacity && (
        <>
          <SectionHeading>Capacity</SectionHeading>
          <KeyValue items={[['Capacity', capacity.text]]} />
          {capacity.fillPct !== null && (
            <div className="mt-1.5">
              <p className="mb-1 text-xs text-muted">
                Design fill{' '}
                <span className="font-mono text-ink">
                  {capacity.fillPct}% (design fill, static)
                </span>
              </p>
              <ProgressBar value={capacity.fillPct} label="Design fill" />
            </div>
          )}
        </>
      )}
      {details.length > 0 && (
        <>
          <SectionHeading>Details</SectionHeading>
          <KeyValue items={details} />
        </>
      )}
      <SectionHeading>Data</SectionHeading>
      <dl className="grid grid-cols-[90px_1fr] gap-x-2 gap-y-1 text-xs">
        <dt className="text-muted">Source</dt>
        <dd className="m-0 font-mono break-words">{p.provenance.source}</dd>
        <dt className="text-muted">Accuracy</dt>
        <dd className="m-0 flex items-center gap-1.5">
          <span
            aria-hidden="true"
            className="inline-block size-2 shrink-0 rounded-full bg-acc-approx"
            style={accuracy ? { backgroundColor: accuracy.colourHex } : undefined}
          />
          {accuracyLabel}
        </dd>
        {p.provenance.capturedOn && (
          <>
            <dt className="text-muted">Captured</dt>
            <dd className="m-0 font-mono">{p.provenance.capturedOn}</dd>
          </>
        )}
        {p.provenance.note && (
          <>
            <dt className="text-muted">Note</dt>
            <dd className="m-0 break-words">{p.provenance.note}</dd>
          </>
        )}
      </dl>
      <Button size="sm" className="mt-4" onClick={clear}>
        Deselect
      </Button>
    </section>
  )
}
