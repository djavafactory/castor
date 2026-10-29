import { Ellipsis } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/overlays'
import { useQueryClient } from '@tanstack/react-query'
import { IconButton } from '@/components/ui/button'
import { fetchAccuracyReport, queryKeys, useDraftLayout } from '@/lib/api'
import { useEditorStore } from '@/lib/stores/editor-store'
import { useUiStore } from '@/lib/stores/ui-store'
import { useServerVersion } from '@/lib/version'
import { HelpContent } from './help-content'

const HELP_TIP = 'Legend, accuracy labels and keyboard shortcuts (?)'
const VERIFY_TIP = 'Check control points and accuracy labels'

function Switch({
  label,
  checked,
  onChange,
}: {
  label: string
  checked: boolean
  onChange: (on: boolean) => void
}) {
  return (
    <label className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-[7px] hover:bg-paper-2">
      <input
        type="checkbox"
        role="switch"
        className="size-4 accent-signal"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
      />
      {label}
    </label>
  )
}

/** Envelope boxes and accuracy outlines: the debug layers of Monitor. Design has them in its bottom strip. */
export function DebugSwitches() {
  const envelope = useUiStore((s) => s.envelope)
  const setEnvelope = useUiStore((s) => s.setEnvelope)
  const accuracy = useUiStore((s) => s.accuracyColouring)
  const setAccuracy = useUiStore((s) => s.setAccuracyColouring)
  return (
    <>
      <Switch label="Envelope boxes" checked={envelope} onChange={setEnvelope} />
      <Switch label="Accuracy outlines" checked={accuracy} onChange={setAccuracy} />
    </>
  )
}

/** More: the Help menu. "Help and legend" opens the legend in place; Design adds "Verify accuracy". */
export function MoreMenu() {
  const [menuOpen, setMenuOpen] = useState(false)
  const helpOpen = useUiStore((s) => s.helpOpen)
  const setHelpOpen = useUiStore((s) => s.setHelpOpen)
  const mode = useUiStore((s) => s.mode)
  const setAccuracyColouring = useUiStore((s) => s.setAccuracyColouring)
  const queryClient = useQueryClient()
  const draft = useDraftLayout(undefined, mode === 'design')
  // Asked only once the help is opened, so a closed menu costs nothing.
  const serverVersion = useServerVersion(helpOpen)

  function onOpenChange(open: boolean) {
    setMenuOpen(open)
    if (!open) setHelpOpen(false)
  }

  /** DESIGNER-11: checks the draft's control points and shape accuracy, then opens the result in the Issues tab. */
  async function verifyAccuracy() {
    setMenuOpen(false)
    setAccuracyColouring(true)
    const layoutId = draft.data?.versionId
    if (layoutId === undefined) {
      toast.error('The accuracy report could not be loaded')
      return
    }
    try {
      await queryClient.fetchQuery({
        queryKey: queryKeys.accuracyReport(layoutId),
        queryFn: ({ signal }) => fetchAccuracyReport(layoutId, signal),
        staleTime: 0,
      })
      useUiStore.getState().setRightTab('issues')
      useEditorStore.getState().logHistory('Ran the accuracy check')
    } catch {
      toast.error('The accuracy report could not be loaded')
    }
  }

  return (
    <Popover open={menuOpen || helpOpen} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <IconButton label="More" tip="More: help, legend and checks">
          <Ellipsis aria-hidden="true" />
        </IconButton>
      </PopoverTrigger>
      {helpOpen ? (
        <PopoverContent
          heading="Help and legend"
          align="end"
          className="max-h-[70vh] w-[360px] overflow-auto"
        >
          <HelpContent serverVersion={serverVersion} />
        </PopoverContent>
      ) : (
        <PopoverContent align="end" className="w-64 p-1">
          <button
            type="button"
            title={HELP_TIP}
            className="flex w-full flex-col rounded-md px-2 py-[7px] text-left hover:bg-paper-2"
            onClick={() => setHelpOpen(true)}
          >
            <span>Help and legend</span>
            <span className="text-xs text-muted">{HELP_TIP}</span>
          </button>
          {mode === 'design' && (
            <button
              type="button"
              title={VERIFY_TIP}
              className="flex w-full flex-col rounded-md px-2 py-[7px] text-left hover:bg-paper-2"
              onClick={() => void verifyAccuracy()}
            >
              <span>Verify accuracy</span>
              <span className="text-xs text-muted">{VERIFY_TIP}</span>
            </button>
          )}
          {mode === 'monitor' && <DebugSwitches />}
        </PopoverContent>
      )}
    </Popover>
  )
}
