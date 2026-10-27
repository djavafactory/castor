import { Layers, Lock, LockOpen } from 'lucide-react'
import { IconButton } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/overlays'
import { layers } from '@/components/scene/layers'
import { cn } from '@/lib/utils'
import { useUiStore } from '@/lib/stores/ui-store'

/**
 * "All layers": one row per layer with a switch, "Isolate" (only this layer shows) and, in Design, a lock.
 * "Show all" restores every layer. A missing key means the layer is visible.
 */
export function LayersMenu() {
  const layerVisibility = useUiStore((s) => s.layerVisibility)
  const setLayerVisible = useUiStore((s) => s.setLayerVisible)
  const isolated = useUiStore((s) => s.isolatedLayer)
  const isolateLayer = useUiStore((s) => s.isolateLayer)
  const showAllLayers = useUiStore((s) => s.showAllLayers)
  const lockedLayers = useUiStore((s) => s.lockedLayers)
  const setLayerLocked = useUiStore((s) => s.setLayerLocked)
  const design = useUiStore((s) => s.mode === 'design')
  const basemap = useUiStore((s) => s.basemap)
  const setBasemap = useUiStore((s) => s.setBasemap)

  return (
    <Popover>
      <PopoverTrigger asChild>
        <IconButton label="All layers" tip="Show, hide or isolate parts of the port">
          <Layers aria-hidden="true" />
        </IconButton>
      </PopoverTrigger>
      <PopoverContent align="start" className="flex max-h-[70vh] w-[300px] flex-col p-0">
        <h3 className="px-3 pt-3 pb-2 text-[11px] font-bold tracking-[0.07em] text-ink uppercase">
          Layers
        </h3>
        <ul className="min-h-0 flex-1 overflow-auto px-3 pb-1">
          {layers().map((layer) => (
            <li key={layer.key} className="flex items-center gap-2 py-1">
              <label className="flex min-w-0 flex-1 cursor-pointer items-center gap-2">
                <input
                  type="checkbox"
                  aria-label={`Show ${layer.label}`}
                  className="size-4 shrink-0 accent-signal"
                  checked={layerVisibility[layer.key] !== false}
                  onChange={(event) => setLayerVisible(layer.key, event.target.checked)}
                />
                <span className="truncate">{layer.label}</span>
              </label>
              {design && !layer.reference && (
                <IconButton
                  label={`Lock ${layer.label}`}
                  tip={
                    lockedLayers[layer.key] ? 'Unlock this layer' : 'Lock this layer against edits'
                  }
                  pressed={lockedLayers[layer.key] === true}
                  className="size-6"
                  onClick={() => setLayerLocked(layer.key, !lockedLayers[layer.key])}
                >
                  {lockedLayers[layer.key] ? (
                    <Lock aria-hidden="true" />
                  ) : (
                    <LockOpen aria-hidden="true" />
                  )}
                </IconButton>
              )}
              <button
                type="button"
                aria-label={`Isolate ${layer.label}`}
                aria-pressed={isolated === layer.key}
                title="Show only this layer"
                className={cn(
                  'h-6 shrink-0 rounded-md border border-line px-2 text-xs font-medium hover:bg-paper-2',
                  isolated === layer.key && 'border-ink bg-ink text-paper hover:bg-ink',
                )}
                onClick={() => isolateLayer(isolated === layer.key ? null : layer.key)}
              >
                Isolate
              </button>
            </li>
          ))}
          <li className="mt-1 border-t border-line-soft pt-2">
            <label className="flex cursor-pointer items-center gap-2">
              <input
                type="checkbox"
                className="size-4 accent-signal"
                checked={basemap === 'sentinel2'}
                onChange={(event) => setBasemap(event.target.checked ? 'sentinel2' : 'none')}
              />
              Satellite imagery (Sentinel-2)
            </label>
          </li>
        </ul>
        <div className="border-t border-line-soft p-2">
          <button
            type="button"
            className="h-7 w-full rounded-md text-[13px] font-medium hover:bg-paper-2"
            onClick={showAllLayers}
          >
            Show all
          </button>
        </div>
      </PopoverContent>
    </Popover>
  )
}
