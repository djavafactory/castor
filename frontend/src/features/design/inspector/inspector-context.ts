import { useMemo } from 'react'
import { toast } from 'sonner'
import { layerOf } from '@/components/scene/layers'
import { subjectOf, type EditSubject } from '@/features/design/edit/edit-model'
import type { RuleContext } from '@/features/design/edit/edit-rules'
import {
  useAssetTypes,
  useCatalogue,
  useDraftLayout,
  usePort,
  type AssetType,
  type Catalogue,
  type Port,
} from '@/lib/api'
import { usePortFrame } from '@/lib/geo/use-port-frame'
import { useUiStore } from '@/lib/stores/ui-store'
import { fieldsOf, shapeKindOf, type AssetFields, type ShapeKind } from './asset-fields'
import { notChanged, useInspectorCommit } from './use-inspector-commit'
import { ruleContextOf, type Proposal } from './shape-edits'

/** Everything the Inspector sections read about the selected asset, loaded once for all of them. */
export interface InspectorContext {
  asset: AssetFields
  type: AssetType
  kind: ShapeKind
  types: ReadonlyMap<string, AssetType>
  /** Every asset of the draft, the selected one included. */
  assets: readonly AssetFields[]
  port: Port
  catalogue: Catalogue
  /** The layer of the asset is locked in "All layers" (Design). */
  locked: boolean
  /** Nothing can be changed: the layer is locked or the version is not the draft. */
  readOnly: boolean
  /** The editable form of the shape, for the geometry edits; null when the shape cannot be edited here. */
  subject: EditSubject | null
  rules: RuleContext
  /** Saves a change of the attributes: one PATCH, one undo step "<label> of <name>". */
  commit: (label: string, changes: Record<string, unknown>) => Promise<boolean>
  /** Saves a geometry change that the move rules have checked; a refusal is shown with its reason. */
  commitProposal: (label: string, proposal: Proposal) => Promise<boolean>
}

/** The Inspector's view of the selected asset, or null while the draft, the types or the port are loading. */
export function useInspectorContext(assetId: string): InspectorContext | null {
  const draft = useDraftLayout()
  const types = useAssetTypes()
  const catalogue = useCatalogue()
  const port = usePort()
  const commitChanges = useInspectorCommit()
  const lockedLayers = useUiStore((s) => s.lockedLayers)

  // the shapes are read in the quay frame: nothing is checked before the port has loaded it
  const frameReady = usePortFrame()
  const assets = useMemo(() => (draft.data?.assets ?? []).map(fieldsOf), [draft.data])
  const typeMap = useMemo(() => new Map((types.data ?? []).map((t) => [t.code, t])), [types.data])
  const asset = assets.find((a) => a.id === assetId)
  const type = asset ? typeMap.get(asset.typeCode) : undefined
  const rules = useMemo(
    () =>
      frameReady && asset && type && port.data
        ? ruleContextOf({ port: port.data, types: typeMap, assets }, asset, type)
        : null,
    [frameReady, asset, type, port.data, typeMap, assets],
  )
  const subject = useMemo(
    () => (frameReady && asset && type ? subjectOf(asset, type) : null),
    [frameReady, asset, type],
  )

  if (!asset || !type || !port.data || !catalogue.data || !rules) return null
  const layer = layerOf(type.code, type.category)
  const locked = layer !== undefined && lockedLayers[layer] === true
  const commit = (label: string, changes: Record<string, unknown>) =>
    commitChanges(asset, label, changes)

  return {
    asset,
    type,
    kind: shapeKindOf(type),
    types: typeMap,
    assets,
    port: port.data,
    catalogue: catalogue.data,
    locked,
    readOnly: locked || draft.data?.status !== 'DRAFT',
    subject,
    rules,
    commit,
    commitProposal: async (label, proposal) => {
      if (!proposal.ok) {
        toast(notChanged(proposal.reason))
        return false
      }
      return commit(label, proposal.changes)
    },
  }
}
