import type { ReactNode } from 'react'
import { SectionHeading } from '@/components/ui/card'
import { detailChange, formatNumber } from '../field-values'
import type { InspectorContext } from '../inspector-context'
import { NumberField, ReadOnlyRow, SelectField } from '../inspector-fields'
import { proposePad, proposeShape, withBerthLength, withRadius, withSize } from '../shape-edits'

const DOOR_SIDES = [
  { value: 'NORTH', label: 'North' },
  { value: 'EAST', label: 'East' },
  { value: 'SOUTH', label: 'South' },
  { value: 'WEST', label: 'West' },
]

/**
 * DIMENSIONS (m): the sizes of the asset's type. Changes of a footprint go through the move rules; the rest is one
 * PATCH of the attribute. The minimums are the catalogue's, and the ones the designer rules set on top.
 */
export function DimensionsSection({ ctx }: { ctx: InspectorContext }) {
  const { asset, type, kind, subject, rules, readOnly, commit, commitProposal } = ctx
  const d = asset.details
  const off = readOnly
  const min = (value: number | null | undefined, fallback: number) => value ?? fallback
  let body: ReactNode = null

  if (type.code === 'STORAGE_PAD') {
    const pad = d.storagePad
    const shape = subject?.shape.kind === 'rect' ? subject.shape : null
    const padChange = (
      label: string,
      change: { bays?: number; rows?: number; bayPitchM?: number; rowPitchM?: number },
    ) =>
      subject ? commitProposal(label, proposePad(subject, rules, change)) : Promise.resolve(false)
    body = (
      <>
        <NumberField
          label="Bays"
          value={pad?.bays}
          spec={{ label: 'Bays', integer: true, min: 1 }}
          disabled={off || !subject}
          onCommit={(v) => padChange('Bays', { bays: v })}
        />
        <NumberField
          label="Rows"
          value={pad?.rows}
          spec={{ label: 'Rows', integer: true, min: 1 }}
          disabled={off || !subject}
          onCommit={(v) => padChange('Rows', { rows: v })}
        />
        <NumberField
          label="Bay pitch"
          unit="m"
          decimals={2}
          value={pad?.bayPitchM}
          spec={{ label: 'Bay pitch', min: 5 }}
          disabled={off || !subject}
          onCommit={(v) => padChange('Bay pitch', { bayPitchM: v })}
        />
        <NumberField
          label="Row pitch"
          unit="m"
          decimals={2}
          value={pad?.rowPitchM}
          spec={{ label: 'Row pitch', min: 5 }}
          disabled={off || !subject}
          onCommit={(v) => padChange('Row pitch', { rowPitchM: v })}
        />
        <NumberField
          label="Max height"
          unit="m"
          decimals={2}
          value={pad?.maxHeightM}
          spec={{ label: 'Max height', min: 1 }}
          disabled={off}
          onCommit={(v) =>
            commit('Max height', detailChange(asset, 'storagePad', { maxHeightM: v }))
          }
        />
        <ReadOnlyRow
          label="Footprint"
          value={
            shape
              ? `${formatNumber(shape.length, 2)} x ${formatNumber(shape.width, 2)} m`
              : `${formatNumber(asset.lengthM ?? 0, 2)} x ${formatNumber(asset.widthM ?? 0, 2)} m`
          }
        />
      </>
    )
  } else if (kind === 'berth') {
    body = (
      <>
        <NumberField
          label="Length"
          unit="m"
          decimals={3}
          value={asset.lengthM}
          spec={{ label: 'Length', min: 20, max: type.max.lengthM ?? undefined }}
          disabled={off || !subject}
          onCommit={(v) =>
            subject
              ? commitProposal(
                  'Length',
                  proposeShape(subject, withBerthLength(subject.shape, v), rules),
                )
              : Promise.resolve(false)
          }
        />
        <NumberField
          label="Depth"
          unit="m"
          decimals={2}
          value={d.berth?.depthM}
          spec={{ label: 'Depth', min: 1 }}
          disabled={off}
          onCommit={(v) => commit('Depth', detailChange(asset, 'berth', { depthM: v }))}
        />
      </>
    )
  } else if (kind === 'quay-point') {
    body = (
      <>
        <NumberField
          label="Height"
          unit="m"
          decimals={2}
          value={asset.heightM}
          spec={{
            label: 'Height',
            min: min(type.min.heightM, 1),
            max: type.max.heightM ?? undefined,
          }}
          disabled={off}
          onCommit={(v) => commit('Height', { heightM: v })}
        />
        <NumberField
          label="Outreach"
          unit="m"
          decimals={2}
          value={d.crane?.outreachM}
          spec={{ label: 'Outreach', min: 1 }}
          disabled={off}
          onCommit={(v) => commit('Outreach', detailChange(asset, 'crane', { outreachM: v }))}
        />
        <NumberField
          label="Backreach"
          unit="m"
          decimals={2}
          value={d.crane?.backreachM}
          spec={{ label: 'Backreach', min: 1 }}
          disabled={off}
          onCommit={(v) => commit('Backreach', detailChange(asset, 'crane', { backreachM: v }))}
        />
        <NumberField
          label="Gauge"
          unit="m"
          decimals={2}
          value={d.crane?.gaugeM}
          spec={{ label: 'Gauge', min: 1 }}
          disabled={off}
          onCommit={(v) => commit('Gauge', detailChange(asset, 'crane', { gaugeM: v }))}
        />
        <NumberField
          label="Safe working load"
          unit="t"
          decimals={2}
          value={d.crane?.safeWorkingLoadT}
          spec={{ label: 'Safe working load', min: 0.01 }}
          disabled={off}
          onCommit={(v) =>
            commit('Safe working load', detailChange(asset, 'crane', { safeWorkingLoadT: v }))
          }
        />
      </>
    )
  } else if (kind === 'rect') {
    const sideSpec = (
      label: string,
      floor: number | null | undefined,
      ceiling: number | null | undefined,
    ) => ({
      label,
      min: min(floor, 2),
      max: ceiling ?? undefined,
    })
    body = (
      <>
        <NumberField
          label="Length"
          unit="m"
          decimals={3}
          value={asset.lengthM}
          spec={sideSpec('Length', type.min.lengthM, type.max.lengthM)}
          disabled={off || !subject}
          onCommit={(v) =>
            subject
              ? commitProposal(
                  'Length',
                  proposeShape(subject, withSize(subject.shape, { length: v }), rules),
                )
              : Promise.resolve(false)
          }
        />
        <NumberField
          label="Width"
          unit="m"
          decimals={3}
          value={asset.widthM}
          spec={sideSpec('Width', type.min.widthM, type.max.widthM)}
          disabled={off || !subject}
          onCommit={(v) =>
            subject
              ? commitProposal(
                  'Width',
                  proposeShape(subject, withSize(subject.shape, { width: v }), rules),
                )
              : Promise.resolve(false)
          }
        />
        <NumberField
          label="Height"
          unit="m"
          decimals={2}
          value={asset.heightM}
          spec={{
            label: 'Height',
            min: min(type.min.heightM, 0.01),
            max: type.max.heightM ?? undefined,
          }}
          disabled={off}
          onCommit={(v) => commit('Height', { heightM: v })}
        />
        {type.code === 'WAREHOUSE' && d.warehouse && (
          <>
            <NumberField
              label="Roof rise"
              unit="m"
              decimals={2}
              value={d.warehouse.roofRiseM}
              spec={{ label: 'Roof rise', min: 0 }}
              disabled={off}
              onCommit={(v) =>
                commit('Roof rise', detailChange(asset, 'warehouse', { roofRiseM: v }))
              }
            />
            <NumberField
              label="Dock doors"
              decimals={0}
              value={d.warehouse.dockDoors}
              spec={{ label: 'Dock doors', integer: true, min: 0 }}
              disabled={off}
              onCommit={(v) =>
                commit('Dock doors', detailChange(asset, 'warehouse', { dockDoors: v }))
              }
            />
            <SelectField
              label="Door side"
              value={d.warehouse.doorSide ?? 'NORTH'}
              options={DOOR_SIDES}
              disabled={off}
              onCommit={(v) =>
                commit('Door side', detailChange(asset, 'warehouse', { doorSide: v ?? 'NORTH' }))
              }
            />
          </>
        )}
      </>
    )
  } else if (kind === 'circle') {
    body = (
      <>
        <NumberField
          label="Radius"
          unit="m"
          decimals={3}
          value={asset.radiusM}
          spec={{
            label: 'Radius',
            min: Math.max(1, min(type.min.radiusM, 1)),
            max: type.max.radiusM ?? undefined,
          }}
          disabled={off || !subject}
          onCommit={(v) =>
            subject
              ? commitProposal('Radius', proposeShape(subject, withRadius(subject.shape, v), rules))
              : Promise.resolve(false)
          }
        />
        {(asset.heightM !== null && asset.heightM !== undefined) ||
        type.defaults.heightM !== null ? (
          <NumberField
            label="Height"
            unit="m"
            decimals={2}
            value={asset.heightM}
            spec={{ label: 'Height', min: min(type.min.heightM, 0.01) }}
            disabled={off}
            onCommit={(v) => commit('Height', { heightM: v })}
          />
        ) : null}
      </>
    )
  } else if (kind === 'line') {
    body = (
      <NumberField
        label="Width"
        unit="m"
        decimals={2}
        value={asset.lineWidthM}
        spec={{ label: 'Width', min: min(type.min.lineWidthM, 0.01) }}
        disabled={off}
        onCommit={(v) => commit('Width', { lineWidthM: v })}
      />
    )
  } else if (kind === 'polygon') {
    body =
      (asset.heightM !== null && asset.heightM !== undefined) || type.defaults.heightM !== null ? (
        <NumberField
          label="Height"
          unit="m"
          decimals={2}
          value={asset.heightM}
          spec={{ label: 'Height', min: min(type.min.heightM, 0.01) }}
          disabled={off}
          onCommit={(v) => commit('Height', { heightM: v })}
        />
      ) : null
  }

  return (
    <section aria-label="Dimensions">
      <SectionHeading>Dimensions (m)</SectionHeading>
      <div className="grid gap-2">
        {body}
        <NumberField
          label="Clearance"
          unit="m"
          decimals={2}
          value={asset.clearanceM}
          spec={{ label: 'Clearance', min: 0 }}
          disabled={off}
          onCommit={(v) => commit('Clearance', { clearanceM: v })}
        />
      </div>
    </section>
  )
}
