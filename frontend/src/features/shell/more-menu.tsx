import { Ellipsis } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/overlays'
import { useQueryClient } from '@tanstack/react-query'
import { IconButton } from '@/components/ui/button'
import { queryKeys } from '@/lib/api/hooks'
import { accuracyReportSchema } from '@/lib/api/schemas'
import { getParsed } from '@/lib/api/validated'
import { getEnv } from '@/lib/env'
import { useUiStore } from '@/lib/stores/ui-store'
import { useServerVersion } from '@/lib/version'
import { HelpContent } from './help-content'

const HELP_TIP = 'Legend, accuracy labels and keyboard shortcuts (?)'

function Switch({
  label,
  checked,
  onChange,
  compact,
}: {
  label: string
  checked: boolean
  onChange: (on: boolean) => void
  compact?: boolean
}) {
  return (
    <label
      className={`flex cursor-pointer items-center gap-2 rounded-md hover:bg-paper-2 ${compact ? 'px-1' : 'px-2 py-[7px]'}`}
    >
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

/** Envelope boxes and accuracy outlines: the debug layers. Design shows them in its bottom strip. */
export function DebugSwitches({ compact }: { compact?: boolean }) {
  const envelope = useUiStore((s) => s.envelope)
  const setEnvelope = useUiStore((s) => s.setEnvelope)
  const accuracy = useUiStore((s) => s.accuracyColouring)
  const setAccuracy = useUiStore((s) => s.setAccuracyColouring)
  return (
    <>
      <Switch label="Envelope boxes" checked={envelope} onChange={setEnvelope} compact={compact} />
      <Switch
        label="Accuracy outlines"
        checked={accuracy}
        onChange={setAccuracy}
        compact={compact}
      />
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
  // Asked only once the help is opened, so a closed menu costs nothing.
  const serverVersion = useServerVersion(helpOpen)

  function onOpenChange(open: boolean) {
    setMenuOpen(open)
    if (!open) setHelpOpen(false)
  }

  async function verifyAccuracy() {
    setMenuOpen(false)
    setAccuracyColouring(true)
    try {
      const code = getEnv().VITE_PORT_CODE
      const report = await queryClient.fetchQuery({
        queryKey: queryKeys.accuracyReport(code),
        queryFn: ({ signal }) =>
          getParsed(`/v1/ports/${code}/accuracy-report`, accuracyReportSchema, { signal }),
        staleTime: 0,
      })
      const approximate = report.byAccuracy.APPROXIMATE ?? 0
      toast(
        `${approximate} of ${report.total} shapes are approximate. Outlines show each accuracy.`,
      )
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
              className="flex w-full rounded-md px-2 py-[7px] text-left hover:bg-paper-2"
              onClick={() => void verifyAccuracy()}
            >
              Verify accuracy
            </button>
          )}
          {mode === 'monitor' && <DebugSwitches />}
        </PopoverContent>
      )}
    </Popover>
  )
}
