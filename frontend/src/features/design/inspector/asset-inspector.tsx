import { Badge, Tag } from '@/components/ui/badge'
import { LAYER_LOCKED_MESSAGE } from '@/components/scene/layers'
import { isEquipmentType } from './asset-fields'
import { useInspectorContext } from './inspector-context'
import { ActionsSection } from './sections/actions-section'
import { DimensionsSection } from './sections/dimensions-section'
import { EquipmentSection } from './sections/equipment-section'
import { IdentitySection } from './sections/identity-section'
import { OperationsSection } from './sections/operations-section'
import { OverrideSection } from './sections/override-section'
import { PositionSection } from './sections/position-section'
import { ProvenanceSection } from './sections/provenance-section'
import { ValidationSection } from './sections/validation-section'

/**
 * The Inspector of one selected asset of the draft (DESIGNER-07): header, then the sections in the order of the ticket.
 * Every field saves itself as it is changed; nothing waits for a button except Check and save.
 */
export function AssetInspector({ assetId }: { assetId: string }) {
  const ctx = useInspectorContext(assetId)
  if (!ctx) return null
  const { asset, type, locked } = ctx
  const approximate = asset.provenance.accuracy === 'APPROXIMATE'
  const equipment = isEquipmentType(type) || asset.details.equipment !== undefined
  return (
    <div className="grid gap-0">
      <header className="flex flex-wrap items-center gap-1.5">
        <h2 className="m-0 min-w-0 text-[15px] font-bold break-words text-ink">
          {asset.name || asset.code}
        </h2>
        <Tag>{type.label}</Tag>
        {approximate && (
          <Badge variant="warn" title="Only a rough imagery-based or synthetic position exists">
            NO SURVEY
          </Badge>
        )}
        {locked && <Badge variant="neutral">LOCKED</Badge>}
      </header>
      {locked && <p className="mt-1 text-xs text-muted">{LAYER_LOCKED_MESSAGE}</p>}
      <IdentitySection ctx={ctx} />
      <PositionSection ctx={ctx} />
      <DimensionsSection ctx={ctx} />
      <OperationsSection ctx={ctx} />
      {equipment && <EquipmentSection ctx={ctx} />}
      <ProvenanceSection ctx={ctx} />
      <OverrideSection ctx={ctx} />
      <ValidationSection ctx={ctx} />
      <ActionsSection ctx={ctx} />
    </div>
  )
}
