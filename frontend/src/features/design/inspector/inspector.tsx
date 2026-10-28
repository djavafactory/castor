import { useEffect } from 'react'
import { Button } from '@/components/ui/button'
import { useSelectionStore } from '@/lib/stores/selection-store'
import { useEditorStore } from '@/lib/stores/editor-store'
import { AssetInspector } from './asset-inspector'
import { useCheckAndSave } from './check-and-save'
import { EmptyInspector } from './empty-inspector'

/** Ctrl+S in Design: the same check as the button, whichever panel tab is open. Mounted with the Design work area. */
export function CheckAndSaveKey() {
  const { run } = useCheckAndSave()
  useEffect(
    () =>
      useEditorStore.subscribe((state, previous) => {
        if (state.saveRequest !== previous.saveRequest) void run()
      }),
    [run],
  )
  return null
}

/**
 * The Design Inspector (right panel, "Inspector" tab): the selected asset in full, or the layout summary and how-to while
 * nothing is selected. "Check and save" sits at the bottom.
 */
export function DesignInspector() {
  const selected = useSelectionStore((s) => s.selectedAssetId)
  const check = useCheckAndSave()
  const { run } = check

  return (
    <div className="flex flex-col">
      {selected ? <AssetInspector assetId={selected} /> : <EmptyInspector />}
      <footer className="mt-5 border-t border-line-soft pt-3">
        <Button
          variant="primary"
          disabled={check.busy}
          title="Wait for pending saves and re-validate (Ctrl+S)"
          onClick={() => void run()}
        >
          {check.busy ? 'Checking...' : 'Check and save'}
        </Button>
        {check.message && (
          <p role="status" className="mt-2 text-xs text-ink">
            {check.message}
          </p>
        )}
      </footer>
    </div>
  )
}
