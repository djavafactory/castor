import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { SectionHeading } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/ui/overlays'
import { goToAsset } from '@/features/search/go-to-place'
import { useCreateAsset, useDeleteAsset, useDuplicateAsset, type NewAsset } from '@/lib/api'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { dependentsOf, fieldsOf, type AssetFields } from '../asset-fields'
import type { InspectorContext } from '../inspector-context'

/** The writable parts of an asset, as the server re-creates it: the copy of a duplicate, for its redo. */
function recreateBody(a: AssetFields): NewAsset {
  return {
    typeCode: a.typeCode,
    id: a.id,
    code: a.code,
    name: a.name,
    geometry: a.geometry,
    anchor: a.anchor,
    headingDeg: a.headingDeg,
    lengthM: a.lengthM,
    widthM: a.widthM,
    heightM: a.heightM,
    radiusM: a.radiusM,
    lineWidthM: a.lineWidthM,
    elevationM: a.elevationM,
    clearanceM: a.clearanceM,
    storageZoneCode: a.storageZoneCode,
    operatorName: a.operatorName,
    description: a.description,
    provenance: a.provenance,
    details: a.details,
    links: [],
  }
}

/**
 * ACTIONS: zoom to the asset, duplicate it (20 m along both axes, "<name> copy"), delete it. Delete asks first and lists
 * the links that go with the asset. Duplicate and delete are each one undo step; a deleted asset comes back with the
 * same id on undo (DESIGNER-08), but its links do not (recreateBody drops them). Everything is disabled on a locked
 * layer.
 */
export function ActionsSection({ ctx }: { ctx: InspectorContext }) {
  const { asset, assets, readOnly } = ctx
  const duplicate = useDuplicateAsset()
  const remove = useDeleteAsset()
  const create = useCreateAsset()
  const [confirmOpen, setConfirmOpen] = useState(false)

  // Delete key (Design): opens the same confirmation as the button, unless the asset cannot be changed
  useEffect(
    () =>
      useEditorStore.subscribe((state, previous) => {
        if (state.deleteRequest !== previous.deleteRequest && !readOnly) setConfirmOpen(true)
      }),
    [readOnly],
  )

  async function copy() {
    let made: AssetFields
    try {
      made = fieldsOf(await duplicate.mutateAsync(asset.id))
    } catch {
      return
    }
    useSelectionStore.getState().select(made.id)
    let current: { rowVersion: number } = made
    useEditorStore.getState().pushHistory({
      label: `Duplicated ${asset.name}`,
      assetName: made.name,
      undo: async () => {
        await remove.mutateAsync({ id: made.id, rowVersion: current.rowVersion })
      },
      redo: async () => {
        // the same id and code come back (re-create, DESIGNER-04)
        current = await create.mutateAsync({ ...recreateBody(made), code: made.code, id: made.id })
      },
    })
  }

  async function doDelete() {
    setConfirmOpen(false)
    const body = recreateBody(asset)
    let current: { rowVersion: number } = asset
    try {
      await remove.mutateAsync({ id: asset.id, rowVersion: asset.rowVersion })
    } catch {
      return
    }
    useSelectionStore.getState().clear()
    useEditorStore.getState().pushHistory({
      label: `Deleted ${asset.name}`,
      assetName: asset.name,
      undo: async () => {
        // the same id and code come back (re-create, DESIGNER-04); its links do not
        current = await create.mutateAsync({ ...body, code: asset.code, id: asset.id })
      },
      redo: async () => {
        await remove.mutateAsync({ id: asset.id, rowVersion: current.rowVersion })
      },
    })
  }

  const links = dependentsOf(asset, assets)
  return (
    <section aria-label="Actions">
      <SectionHeading>Actions</SectionHeading>
      <div className="flex flex-wrap gap-2">
        <Button size="sm" onClick={() => goToAsset(asset.id, asset.geometry)}>
          Zoom to
        </Button>
        <Button size="sm" disabled={readOnly} onClick={() => void copy()}>
          Duplicate
        </Button>
        <Button size="sm" variant="danger" disabled={readOnly} onClick={() => setConfirmOpen(true)}>
          Delete
        </Button>
      </div>
      <ConfirmDialog
        open={confirmOpen}
        title={`Delete ${asset.name}?`}
        description={
          links.length > 0
            ? `The asset leaves the draft. These links go with it: ${links.join('; ')}.`
            : 'The asset leaves the draft. No link depends on it.'
        }
        confirmLabel="Delete"
        danger
        onConfirm={() => void doDelete()}
        onCancel={() => setConfirmOpen(false)}
      />
    </section>
  )
}
