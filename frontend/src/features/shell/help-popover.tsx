import { useState } from 'react'
import { TilesNote } from '@/components/states/states'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/overlays'
import { useUiStore } from '@/lib/stores/ui-store'
import { useServerVersion, versionLine } from '@/lib/version'

export function HelpPopover() {
  const [open, setOpen] = useState(false)
  // Asked only once the popover is opened, so a closed Help costs nothing.
  const serverVersion = useServerVersion(open)
  const imageryFailed = useUiStore((s) => s.imageryFailed && s.basemap === 'sentinel2')

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
        <p className="text-xs text-muted">{versionLine(serverVersion)}</p>
        {imageryFailed && <TilesNote />}
      </PopoverContent>
    </Popover>
  )
}
