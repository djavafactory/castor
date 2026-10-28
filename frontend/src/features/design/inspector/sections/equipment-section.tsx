import { SectionHeading } from '@/components/ui/card'
import { detailChange } from '../field-values'
import type { InspectorContext } from '../inspector-context'
import { NumberField, SelectField, TextField } from '../inspector-fields'

const THROUGHPUT_UNITS = ['t/h', 'lifts/h', 'm3/h', 'veh/h'].map((u) => ({ value: u, label: u }))
const DESIGN_STATUS = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'IDLE', label: 'Idle' },
  { value: 'OFFLINE', label: 'Offline' },
  { value: 'MAINTENANCE', label: 'Maintenance' },
]

/**
 * The equipment group: a model, its operator, a rated throughput with its unit, and a design status (a static design
 * value, never live status). The throughput and its unit are stored together, so the unit follows the value.
 */
export function EquipmentSection({ ctx }: { ctx: InspectorContext }) {
  const { asset, readOnly, commit } = ctx
  const e = asset.details.equipment
  const off = readOnly
  const equipment = (members: Record<string, unknown>) =>
    detailChange(asset, 'equipment', {
      model: null,
      operator: null,
      ratedThroughput: null,
      throughputUnit: null,
      designStatus: 'ACTIVE',
      ...e,
      ...members,
    })

  return (
    <section aria-label="Equipment">
      <SectionHeading>Equipment</SectionHeading>
      <div className="grid gap-2">
        <TextField
          label="Model"
          value={e?.model}
          maxLength={120}
          disabled={off}
          onCommit={(model) => commit('Model', equipment({ model: model || null }))}
        />
        <TextField
          label="Equipment operator"
          value={e?.operator}
          maxLength={120}
          disabled={off}
          onCommit={(operator) =>
            commit('Equipment operator', equipment({ operator: operator || null }))
          }
        />
        <NumberField
          label="Rated throughput"
          decimals={2}
          value={e?.ratedThroughput}
          spec={{ label: 'Rated throughput', min: 0.01 }}
          disabled={off}
          onCommit={(v) =>
            commit(
              'Rated throughput',
              equipment({ ratedThroughput: v, throughputUnit: e?.throughputUnit ?? 't/h' }),
            )
          }
        />
        <SelectField
          label="Throughput unit"
          value={e?.throughputUnit}
          options={THROUGHPUT_UNITS}
          disabled={off || !e?.ratedThroughput}
          onCommit={(unit) =>
            unit === null
              ? Promise.resolve(false)
              : commit('Throughput unit', equipment({ throughputUnit: unit }))
          }
        />
        <SelectField
          label="Design status"
          value={e?.designStatus ?? 'ACTIVE'}
          options={DESIGN_STATUS}
          disabled={off}
          onCommit={(status) =>
            status === null
              ? Promise.resolve(false)
              : commit('Design status', equipment({ designStatus: status }))
          }
        />
      </div>
    </section>
  )
}
