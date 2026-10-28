import { useId } from 'react'
import { Input } from '@/components/ui/field'
import { SectionHeading } from '@/components/ui/card'
import { provenanceChange } from '../field-values'
import type { InspectorContext } from '../inspector-context'
import { FieldRow, SelectField, TextField } from '../inspector-fields'

/**
 * PROVENANCE AND ACCURACY: where the shape comes from and how far it can be trusted. Sources that may not be stored
 * (imagery that may only be looked at) are not offered. Every change here is one undo step, "Provenance".
 */
export function ProvenanceSection({ ctx }: { ctx: InspectorContext }) {
  const { asset, port, catalogue, readOnly, commit } = ctx
  const p = asset.provenance
  const dateId = useId()
  const sources = port.dataSources
    .filter((s) => s.mayPersist || s.code === p.source)
    .map((s) => ({ value: s.code, label: `${s.name} (${s.code})` }))
  const accuracy = catalogue.accuracyClasses
  const current = accuracy.find((a) => a.code === p.accuracy)
  const save = (members: Record<string, unknown>) =>
    commit('Provenance', provenanceChange(asset, members))

  return (
    <section aria-label="Provenance and accuracy">
      <SectionHeading>Provenance and accuracy</SectionHeading>
      <div className="grid gap-2">
        <SelectField
          label="Source"
          value={p.source}
          options={sources}
          disabled={readOnly}
          onCommit={(source) => (source === null ? Promise.resolve(false) : save({ source }))}
        />
        <SelectField
          label="Accuracy"
          value={p.accuracy}
          options={accuracy.map((a) => ({ value: a.code, label: a.label }))}
          dot={current?.colourHex}
          disabled={readOnly}
          onCommit={(accuracyCode) =>
            accuracyCode === null ? Promise.resolve(false) : save({ accuracy: accuracyCode })
          }
        />
        <FieldRow label="Captured" htmlFor={dateId}>
          <Input
            id={dateId}
            type="date"
            value={p.capturedOn ?? ''}
            disabled={readOnly}
            onChange={(event) => void save({ capturedOn: event.target.value || null })}
          />
        </FieldRow>
        <TextField
          label="Note"
          value={p.note}
          multiline
          maxLength={500}
          disabled={readOnly}
          onCommit={(note) => save({ note: note || null })}
        />
      </div>
      <p className="mt-2 text-xs text-muted">
        False accuracy is worse than an honest approximate label: only mark a shape Surveyed when a
        survey backs it.
      </p>
    </section>
  )
}
