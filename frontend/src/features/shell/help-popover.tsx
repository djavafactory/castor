import { useState } from 'react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/overlays'
import { getEnv } from '@/lib/env'
import { useServerVersion, versionLine } from '@/lib/version'

export function HelpPopover() {
  const [open, setOpen] = useState(false)
  // Asked only once the popover is opened, so a closed Help costs nothing.
  const serverVersion = useServerVersion(open)

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="h-8 rounded-lg px-2 text-sm font-medium hover:bg-paper-2"
          aria-label="Help"
        >
          Help
        </button>
      </PopoverTrigger>
      <PopoverContent heading="Help" align="end" className="w-72">
        <p className="text-xs text-muted">{versionLine(getEnv().VITE_APP_ENV, serverVersion)}</p>
      </PopoverContent>
    </Popover>
  )
}
