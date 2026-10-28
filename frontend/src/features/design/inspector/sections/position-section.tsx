import { useMemo } from 'react'
import { Button } from '@/components/ui/button'
import { SectionHeading } from '@/components/ui/card'
import { fromLonLat, type Local } from '@/lib/geo'
import { linePointsOf, ringOf } from '../asset-fields'
import { formatNumber } from '../field-values'
import type { InspectorContext } from '../inspector-context'
import { NumberField, ReadOnlyRow, FieldRow } from '../inspector-fields'
import {
  localBounds,
  proposeShape,
  slideAlongQuay,
  withAnchor,
  withHeading,
  withVertex,
  withoutVertex,
} from '../shape-edits'

const coordinate = (v: number) => v.toFixed(7)

/** POSITION (GPS): where the asset stands, in the stored coordinates and in the quay frame. */
export function PositionSection({ ctx }: { ctx: InspectorContext }) {
  const { asset, kind } = ctx
  const [lon, lat] = asset.anchor
  return (
    <section aria-label="Position">
      <SectionHeading>Position (GPS)</SectionHeading>
      <div className="grid gap-2">
        {kind === 'berth' || kind === 'quay-point' ? (
          <>
            <ChainageField ctx={ctx} />
            <ReadOnlyRow label="Longitude" value={coordinate(lon)} />
            <ReadOnlyRow label="Latitude" value={coordinate(lat)} />
          </>
        ) : kind === 'polygon' || kind === 'line' ? (
          <Outline ctx={ctx} />
        ) : (
          <LatLng ctx={ctx} />
        )}
        {(kind === 'rect' || kind === 'quay-point') && <HeadingField ctx={ctx} />}
        <ReadOnlyRow label="Local" value={localText(asset.computed.local)} />
        <ReadOnlyRow
          label="Elevation"
          value={`ground ${formatNumber(asset.elevationM ?? 0, 3)} m (flat); height datum not set`}
        />
        <ReadOnlyRow
          label="Model template"
          value={`${asset.typeCode} rev ${asset.modelRev ?? '—'} (procedural low-poly)`}
        />
        <ReadOnlyRow label="Envelope" value={envelopeText(ctx)} />
      </div>
    </section>
  )
}

function localText(local: { x?: number; y?: number } | undefined): string {
  if (!local || local.x === undefined || local.y === undefined) return '—'
  return `x ${local.x.toFixed(2)} m, y ${local.y.toFixed(2)} m`
}

/** "L x W x H m" from the shape; the outline's box for polygons and lines. */
function envelopeText(ctx: InspectorContext): string {
  const { asset, kind, subject } = ctx
  const height =
    asset.heightM === null || asset.heightM === undefined ? '—' : formatNumber(asset.heightM, 2)
  if (subject && (kind === 'rect' || kind === 'quay-point') && subject.shape.kind === 'rect') {
    return `${formatNumber(subject.shape.length, 2)} x ${formatNumber(subject.shape.width, 2)} x ${height} m`
  }
  if (subject && subject.shape.kind === 'circle') {
    const side = formatNumber(subject.shape.radius * 2, 2)
    return `${side} x ${side} x ${height} m`
  }
  const points = outlinePoints(ctx)
  if (points.length > 0) {
    const box = localBounds([points])
    return `${formatNumber(box.width, 2)} x ${formatNumber(box.height, 2)} x ${height} m`
  }
  return `— x — x ${height} m`
}

/** The stored vertices of the asset's outline as local metres. */
function outlinePoints(ctx: InspectorContext): Local[] {
  const geometry = ctx.asset.geometry
  const lonLats = ringOf(geometry) ?? linePointsOf(geometry) ?? []
  return lonLats.map((p) => fromLonLat(p[0], p[1]))
}

/** Berths and rail cranes: the chainage (local x along the quay) moves the asset along the quay line. */
function ChainageField({ ctx }: { ctx: InspectorContext }) {
  const { asset, subject, rules, readOnly, commitProposal } = ctx
  const shape = subject?.shape
  const current =
    shape?.kind === 'berth'
      ? shape.a[0]
      : shape?.kind === 'rect'
        ? shape.anchor[0]
        : (asset.computed.chainage?.startM ?? asset.computed.local?.x ?? null)
  return (
    <NumberField
      label="Chainage"
      unit="m"
      decimals={3}
      value={current}
      spec={{ label: 'Chainage' }}
      disabled={readOnly || !subject || !shape}
      onCommit={async (value) => {
        if (!subject || !shape) return false
        return commitProposal(
          'Chainage',
          proposeShape(subject, slideAlongQuay(shape, value - (current ?? 0)), rules),
        )
      }}
    />
  )
}

/** Other points, rectangles and circles: latitude and longitude of the anchor, 7 decimals. */
function LatLng({ ctx }: { ctx: InspectorContext }) {
  const { asset, subject, rules, readOnly, commitProposal, commit } = ctx
  const [lon, lat] = asset.anchor
  const setAnchor = async (next: [number, number], label: string) => {
    if (!subject) return commit(label, { anchor: next })
    return commitProposal(
      label,
      proposeShape(subject, withAnchor(subject.shape, fromLonLat(next[0], next[1])), rules),
    )
  }
  return (
    <>
      <NumberField
        label="Latitude"
        decimals={7}
        value={lat}
        spec={{ label: 'Latitude', min: -90, max: 90 }}
        disabled={readOnly}
        onCommit={(value) => setAnchor([lon, value], 'Latitude')}
      />
      <NumberField
        label="Longitude"
        decimals={7}
        value={lon}
        spec={{ label: 'Longitude', min: -180, max: 180 }}
        disabled={readOnly}
        onCommit={(value) => setAnchor([value, lat], 'Longitude')}
      />
    </>
  )
}

/** Rectangles and rail cranes: the heading from true north, one decimal. */
function HeadingField({ ctx }: { ctx: InspectorContext }) {
  const { asset, subject, rules, readOnly, commitProposal } = ctx
  return (
    <NumberField
      label="Heading"
      unit="deg T"
      decimals={1}
      value={asset.headingDeg}
      spec={{ label: 'Heading' }}
      disabled={readOnly || !subject}
      onCommit={(value) => {
        if (!subject) return Promise.resolve(false)
        return commitProposal(
          'Heading',
          proposeShape(subject, withHeading(subject.shape, value), rules),
        )
      }}
    />
  )
}

/** Polygons and lines: the vertex table, with the perimeter or length and the area (polygons). */
function Outline({ ctx }: { ctx: InspectorContext }) {
  const { asset, kind, subject, rules, readOnly, commitProposal } = ctx
  const vertices = useMemo(
    () => ringOf(asset.geometry) ?? linePointsOf(asset.geometry) ?? [],
    [asset.geometry],
  )
  const minimum = kind === 'polygon' ? 3 : 2
  const editable = !readOnly && subject !== null
  const area = asset.computed.areaM2
  return (
    <>
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-xs">
          <caption className="sr-only">Vertices</caption>
          <thead>
            <tr>
              <th scope="col" className="px-1 py-1 text-left font-semibold text-muted">
                #
              </th>
              <th scope="col" className="px-1 py-1 text-left font-semibold text-muted">
                Longitude
              </th>
              <th scope="col" className="px-1 py-1 text-left font-semibold text-muted">
                Latitude
              </th>
              <th scope="col" className="px-1 py-1">
                <span className="sr-only">Remove</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {vertices.map(([lon, lat], i) => (
              <tr key={i} className="border-t border-line-soft">
                <td className="px-1 py-1 font-mono text-muted">{i + 1}</td>
                <td className="px-1 py-1">
                  <NumberField
                    bare
                    label={`Longitude of vertex ${i + 1}`}
                    decimals={7}
                    value={lon}
                    spec={{ label: 'Longitude', min: -180, max: 180 }}
                    disabled={!editable}
                    onCommit={(value) => {
                      if (!subject) return Promise.resolve(false)
                      const point = fromLonLat(value, lat)
                      return commitProposal(
                        `Vertex ${i + 1}`,
                        proposeShape(subject, withVertex(subject.shape, i, point), rules),
                      )
                    }}
                  />
                </td>
                <td className="px-1 py-1">
                  <NumberField
                    bare
                    label={`Latitude of vertex ${i + 1}`}
                    decimals={7}
                    value={lat}
                    spec={{ label: 'Latitude', min: -90, max: 90 }}
                    disabled={!editable}
                    onCommit={(value) => {
                      if (!subject) return Promise.resolve(false)
                      const point = fromLonLat(lon, value)
                      return commitProposal(
                        `Vertex ${i + 1}`,
                        proposeShape(subject, withVertex(subject.shape, i, point), rules),
                      )
                    }}
                  />
                </td>
                <td className="px-1 py-1 text-right">
                  <Button
                    size="sm"
                    variant="ghost"
                    aria-label={`Remove vertex ${i + 1}`}
                    disabled={!editable || vertices.length <= minimum}
                    onClick={() => {
                      if (!subject) return
                      const next = withoutVertex(subject.shape, i)
                      if (!next) return
                      void commitProposal('Remove vertex', proposeShape(subject, next, rules))
                    }}
                  >
                    x
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ReadOnlyRow
        label={kind === 'polygon' ? 'Perimeter' : 'Length'}
        value={`${formatNumber(kind === 'polygon' ? (asset.computed.perimeterM ?? 0) : (asset.computed.lengthM ?? 0), 2)} m`}
      />
      {kind === 'polygon' && (
        <FieldRow label="Area">
          <p className="m-0 font-mono text-xs text-ink">
            {area === null || area === undefined
              ? '—'
              : `${area.toFixed(1)} m2 (${(area / 10000).toFixed(3)} ha)`}
          </p>
        </FieldRow>
      )}
    </>
  )
}
