import { SectionHeading } from '@/components/ui/card'
import { isStorageType } from '../asset-fields'
import type { InspectorContext } from '../inspector-context'
import { ReadOnlyRow, TextField } from '../inspector-fields'

/** ASSET: type and code are fixed; name, storage zone, operator and description are the editor's. */
export function IdentitySection({ ctx }: { ctx: InspectorContext }) {
  const { asset, type, readOnly, commit } = ctx
  return (
    <section aria-label="Asset properties">
      <SectionHeading className="mt-0">Asset</SectionHeading>
      <div className="grid gap-2">
        <ReadOnlyRow label="Type" value={type.label} />
        <TextField
          label="Name"
          value={asset.name}
          required
          maxLength={120}
          disabled={readOnly}
          onCommit={(name) => commit('Name', { name })}
        />
        <ReadOnlyRow label="Code" value={asset.code} />
        {isStorageType(type) && (
          <TextField
            label="Storage zone code"
            value={asset.storageZoneCode}
            maxLength={40}
            disabled={readOnly}
            onCommit={(storageZoneCode) =>
              commit('Storage zone code', { storageZoneCode: storageZoneCode || null })
            }
          />
        )}
        <TextField
          label="Operator"
          value={asset.operatorName}
          maxLength={120}
          disabled={readOnly}
          onCommit={(operatorName) => commit('Operator', { operatorName: operatorName || null })}
        />
        <TextField
          label="Description"
          value={asset.description}
          multiline
          maxLength={500}
          disabled={readOnly}
          onCommit={(description) => commit('Description', { description: description || null })}
        />
      </div>
    </section>
  )
}
