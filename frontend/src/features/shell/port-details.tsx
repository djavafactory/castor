import { KeyValue, SectionHeading } from '@/components/ui/card'
import { useCurrentLayout, usePort } from '@/lib/api'
import { formatPublishedAt } from './published-label'

/** The PORT section shown while nothing is selected. */
export function PortDetails() {
  const port = usePort()
  const layout = useCurrentLayout()
  const p = port.data
  const header = layout.data?.data.layout
  const features = layout.data?.data.features.features ?? []
  const pads = features.filter((f) => f.properties.type === 'STORAGE_PAD').length
  const berths = features.filter((f) => f.properties.type === 'BERTH')
  const quay = berths.reduce(
    (sum, f) => sum + (f.properties.lengthM ?? f.properties.computed.lengthM ?? 0),
    0,
  )

  const items: [string, string][] = []
  if (p) items.push(['Name', p.name])
  if (layout.data) {
    items.push(['Berths', `${berths.length} (${Math.round(quay)} m of quay)`])
    items.push(['Storage pads', String(pads)])
    items.push(['Assets', String(features.length)])
  }
  if (header) {
    const when = header.publishedAt
      ? `, published ${formatPublishedAt(header.publishedAt, p?.timeZone)}`
      : ''
    items.push([
      'Layout',
      `v${header.versionNo}${when}${header.publishedBy ? ` by ${header.publishedBy}` : ''}`,
    ])
  }
  items.push(['CRS', `WGS84 lon/lat; metres in EPSG:${p?.measureSrid ?? 3380}`])

  return (
    <>
      <p className="text-[13px] text-muted">Select something in the 3D view to see its details.</p>
      <SectionHeading>Port</SectionHeading>
      <KeyValue items={items} />
      {p?.synthetic && (
        <p className="mt-3 text-xs text-muted">
          Berths, yards and equipment are synthetic placeholders, not a survey of Westports.
        </p>
      )}
    </>
  )
}
