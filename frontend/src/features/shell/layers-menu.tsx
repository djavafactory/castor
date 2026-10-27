import { Layers } from 'lucide-react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/overlays'
import { LAYERS } from '@/components/scene/layers'
import { useUiStore } from '@/lib/stores/ui-store'

/** "All layers": one switch per layer of the stage. A missing key means the layer is visible. */
export function LayersMenu() {
  const layerVisibility = useUiStore((s) => s.layerVisibility)
  const setLayerVisible = useUiStore((s) => s.setLayerVisible)

  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="inline-flex h-7 items-center gap-1.5 rounded-lg px-2 text-[13px] font-medium hover:bg-paper-2"
        >
          <Layers aria-hidden="true" className="size-4" />
          All layers
        </button>
      </PopoverTrigger>
      <PopoverContent heading="All layers" align="start" className="w-64">
        <ul className="flex flex-col gap-1.5">
          {LAYERS.map((layer) => (
            <li key={layer.key}>
              <label className="flex cursor-pointer items-center gap-2">
                <input
                  type="checkbox"
                  className="size-4 accent-signal"
                  checked={layerVisibility[layer.key] !== false}
                  onChange={(event) => setLayerVisible(layer.key, event.target.checked)}
                />
                {layer.label}
              </label>
            </li>
          ))}
        </ul>
      </PopoverContent>
    </Popover>
  )
}
