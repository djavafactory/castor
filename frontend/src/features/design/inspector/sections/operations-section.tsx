import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/field'
import { SectionHeading } from '@/components/ui/card'
import { isStorageType, LINK_KINDS, LINK_LABELS } from '../asset-fields'
import { storageChange } from '../storage-record'
import { detailChange, fromPercent, toPercent } from '../field-values'
import type { InspectorContext } from '../inspector-context'
import { CheckList, NumberField, SelectField } from '../inspector-fields'

const CAPACITY_UNITS = ['t', 'm3', 'pcs', 'veh', 'TEU', 'trucks'].map((u) => ({
  value: u,
  label: u,
}))
const FLOW_UNITS = ['t/h', 'm3/h'].map((u) => ({ value: u, label: u }))
const POSITION_MODES = [
  { value: 'FIXED', label: 'Fixed' },
  { value: 'PARKED', label: 'Parked' },
]
const WEATHER = [
  { value: 'true', label: 'Exposed' },
  { value: 'false', label: 'Covered' },
]

/** OPERATIONAL SEMANTICS: what the asset holds, how fast it moves things, what it is allowed to hold and who it serves. */
export function OperationsSection({ ctx }: { ctx: InspectorContext }) {
  const { asset, type, catalogue, readOnly, commit } = ctx
  const d = asset.details
  const off = readOnly
  const storage = d.storage
  const cargo = catalogue.cargoTypes.map((c) => ({ value: c.code, label: c.name }))
  const surfaces = catalogue.surfaces.map((s) => ({ value: s.code, label: s.label }))
  const allowedTypes = [...ctx.types.values()]
    .filter((t) => t.code !== 'EXCLUSION_ZONE')
    .map((t) => ({ value: t.code, label: t.label }))
    .sort((a, b) => a.label.localeCompare(b.label))

  return (
    <section aria-label="Operational semantics">
      <SectionHeading>Operational semantics</SectionHeading>
      <div className="grid gap-2">
        {(isStorageType(type) || storage) && (
          <>
            <SelectField
              label="Cargo type"
              value={storage?.cargoTypeCode}
              options={cargo}
              emptyLabel="None"
              disabled={off}
              onCommit={(v) => commit('Cargo type', storageChange(ctx, { cargoTypeCode: v }))}
            />
            {!storage && (
              <p className="text-xs text-muted">Choose a cargo type to start the storage record.</p>
            )}
            <NumberField
              label="Capacity"
              value={storage?.capacityValue}
              spec={{ label: 'Capacity', min: 0 }}
              disabled={off || !storage}
              onCommit={(v) => commit('Capacity', storageChange(ctx, { capacityValue: v }))}
            />
            <SelectField
              label="Capacity unit"
              value={storage?.capacityUnit}
              options={CAPACITY_UNITS}
              disabled={off || !storage}
              onCommit={(v) =>
                v
                  ? commit('Capacity unit', storageChange(ctx, { capacityUnit: v }))
                  : Promise.resolve(false)
              }
            />
            <NumberField
              label="Usable share"
              unit="%"
              decimals={1}
              value={toPercent(storage?.usableRatio)}
              spec={{ label: 'Usable share', min: 1, max: 100 }}
              disabled={off || !storage}
              onCommit={(v) =>
                commit('Usable share', storageChange(ctx, { usableRatio: fromPercent(v) }))
              }
            />
            <NumberField
              label="Design fill"
              unit="%"
              decimals={1}
              value={toPercent(storage?.designFill)}
              spec={{ label: 'Design fill', min: 0, max: 100 }}
              disabled={off || !storage}
              onCommit={(v) =>
                commit('Design fill', storageChange(ctx, { designFill: fromPercent(v) }))
              }
            />
            <NumberField
              label="Max floor load"
              unit="t/m2"
              decimals={2}
              value={storage?.maxFloorLoadTM2}
              spec={{ label: 'Max floor load', min: 0.01 }}
              disabled={off || !storage}
              onCommit={(v) => commit('Max floor load', storageChange(ctx, { maxFloorLoadTM2: v }))}
            />
            <NumberField
              label="Max stack height"
              unit="m"
              decimals={2}
              value={storage?.maxStackM}
              spec={{ label: 'Max stack height', min: 0.01 }}
              disabled={off || !storage}
              onCommit={(v) => commit('Max stack height', storageChange(ctx, { maxStackM: v }))}
            />
            <NumberField
              label="Bearing capacity"
              unit="kPa"
              decimals={1}
              value={storage?.bearingCapacityKpa}
              spec={{ label: 'Bearing capacity', min: 0.1 }}
              disabled={off || !storage}
              onCommit={(v) =>
                commit('Bearing capacity', storageChange(ctx, { bearingCapacityKpa: v }))
              }
            />
            <SelectField
              label="Weather"
              value={storage ? String(storage.weatherExposed) : undefined}
              options={WEATHER}
              disabled={off || !storage}
              onCommit={(v) =>
                v === null
                  ? Promise.resolve(false)
                  : commit('Weather', storageChange(ctx, { weatherExposed: v === 'true' }))
              }
            />
            <CheckList
              label="Allowed cargo"
              options={cargo}
              selected={storage?.allowedCargoTypes ?? []}
              disabled={off || !storage}
              onCommit={(next) =>
                commit('Allowed cargo', storageChange(ctx, { allowedCargoTypes: next }))
              }
            />
          </>
        )}
        {d.containerYard && (
          <>
            <NumberField
              label="Max tiers"
              decimals={0}
              value={d.containerYard.maxTiers}
              spec={{ label: 'Max tiers', integer: true, min: 1, max: 9 }}
              disabled={off}
              onCommit={(v) =>
                commit('Max tiers', detailChange(asset, 'containerYard', { maxTiers: v }))
              }
            />
            <NumberField
              label="Yard design fill"
              unit="%"
              decimals={1}
              value={toPercent(d.containerYard.designFill)}
              spec={{ label: 'Yard design fill', min: 0, max: 100 }}
              disabled={off}
              onCommit={(v) =>
                commit(
                  'Yard design fill',
                  detailChange(asset, 'containerYard', { designFill: fromPercent(v) }),
                )
              }
            />
          </>
        )}
        {d.flow && (
          <>
            <NumberField
              label="Conveyor rate"
              decimals={2}
              value={d.flow.rateValue}
              spec={{ label: 'Conveyor rate', min: 0.01 }}
              disabled={off}
              onCommit={(v) =>
                commit('Conveyor rate', detailChange(asset, 'flow', { rateValue: v }))
              }
            />
            <SelectField
              label="Rate unit"
              value={d.flow.rateUnit}
              options={FLOW_UNITS}
              disabled={off}
              onCommit={(v) =>
                v === null
                  ? Promise.resolve(false)
                  : commit('Rate unit', detailChange(asset, 'flow', { rateUnit: v }))
              }
            />
          </>
        )}
        {d.surface && (
          <SelectField
            label="Surface"
            value={d.surface.surfaceCode}
            options={surfaces}
            disabled={off}
            onCommit={(v) =>
              v === null
                ? Promise.resolve(false)
                : commit('Surface', detailChange(asset, 'surface', { surfaceCode: v }))
            }
          />
        )}
        {(type.flags.mobile || d.mobile) && (
          <>
            <SelectField
              label="Position mode"
              value={d.mobile?.positionMode ?? 'PARKED'}
              options={POSITION_MODES}
              disabled={off}
              onCommit={(v) =>
                v === null
                  ? Promise.resolve(false)
                  : commit(
                      'Position mode',
                      detailChange(asset, 'mobile', {
                        positionMode: v,
                        uncertaintyM: d.mobile?.uncertaintyM ?? 5,
                      }),
                    )
              }
            />
            <NumberField
              label="Uncertainty"
              unit="m"
              decimals={2}
              value={d.mobile?.uncertaintyM ?? 5}
              spec={{ label: 'Uncertainty', min: 0 }}
              disabled={off}
              onCommit={(v) =>
                commit(
                  'Uncertainty',
                  detailChange(asset, 'mobile', {
                    positionMode: d.mobile?.positionMode ?? 'PARKED',
                    uncertaintyM: v,
                  }),
                )
              }
            />
          </>
        )}
        {(asset.typeCode === 'EXCLUSION_ZONE' || d.zone) && (
          <CheckList
            label="Allowed types inside"
            options={allowedTypes}
            selected={d.zone?.allowedTypes ?? []}
            disabled={off}
            onCommit={(next) =>
              commit('Allowed types', detailChange(asset, 'zone', { allowedTypes: next }))
            }
          />
        )}
        <LinksField ctx={ctx} />
      </div>
    </section>
  )
}

/** Links to other assets of the draft ("Serves berth", "Feeds"): added and removed here, each change one PATCH. */
function LinksField({ ctx }: { ctx: InspectorContext }) {
  const { asset, assets, readOnly, commit } = ctx
  const [kind, setKind] = useState<string>(LINK_KINDS[0] ?? 'SERVES')
  const [target, setTarget] = useState('')
  const nameOf = (id: string) => assets.find((a) => a.id === id)?.name ?? 'an asset'
  const others = assets.filter((a) => a.id !== asset.id)
  const links = asset.links
  const duplicate = links.some((l) => l.kind === kind && l.toAssetId === target)

  return (
    <div className="grid gap-1.5">
      <p className="m-0 text-xs text-muted">Links</p>
      {links.length === 0 ? (
        <p className="m-0 text-xs text-muted">No links.</p>
      ) : (
        <ul className="m-0 grid list-none gap-1 p-0">
          {links.map((link) => (
            <li
              key={`${link.kind}:${link.toAssetId}`}
              className="flex items-center justify-between gap-2 text-[13px]"
            >
              <span className="min-w-0 truncate">
                {LINK_LABELS[link.kind]} {nameOf(link.toAssetId)}
              </span>
              <Button
                size="sm"
                variant="ghost"
                aria-label={`Remove link: ${LINK_LABELS[link.kind]} ${nameOf(link.toAssetId)}`}
                disabled={readOnly}
                onClick={() =>
                  void commit('Links', {
                    links: links
                      .filter((l) => l !== link)
                      .map((l) => ({ kind: l.kind, toAssetId: l.toAssetId })),
                  })
                }
              >
                x
              </Button>
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap items-center gap-1.5">
        <Select
          aria-label="Link kind"
          className="w-auto"
          value={kind}
          disabled={readOnly}
          onChange={(e) => setKind(e.target.value)}
        >
          {LINK_KINDS.map((k) => (
            <option key={k} value={k}>
              {LINK_LABELS[k]}
            </option>
          ))}
        </Select>
        <Select
          aria-label="Link target"
          className="w-auto min-w-0 flex-1"
          value={target}
          disabled={readOnly}
          onChange={(e) => setTarget(e.target.value)}
        >
          <option value="">Choose an asset</option>
          {others.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </Select>
        <Button
          size="sm"
          disabled={readOnly || target === '' || duplicate}
          onClick={() => {
            void commit('Links', {
              links: [
                ...links.map((l) => ({ kind: l.kind, toAssetId: l.toAssetId })),
                { kind, toAssetId: target },
              ],
            })
            setTarget('')
          }}
        >
          Add link
        </Button>
      </div>
    </div>
  )
}
