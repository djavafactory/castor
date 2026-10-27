import { useAssetTypes, type AssetType } from '@/lib/api'
import { cn } from '@/lib/utils'
import { useEditorStore } from '@/lib/stores/editor-store'
import { shapeOf } from './draw-rules'
import { startDraw } from './start-draw'

const SHAPE_LABEL = { polygon: 'Outlines', line: 'Lines' } as const

/** Types that are traced vertex by vertex on the 2D map (and the point types the same tool places by one click). */
export function DrawTypes() {
  const types = useAssetTypes()
  const active = useEditorStore((s) => (s.tool?.kind === 'draw' ? s.tool.typeCode : null))
  const traced = (types.data ?? []).filter((t) => t.placementMode === 'DRAW')
  if (traced.length === 0) return null

  return (
    <div className="mt-3 flex flex-col gap-3" data-testid="draw-types">
      {(['polygon', 'line'] as const).map((shape) => (
        <section key={shape} aria-label={`Draw ${SHAPE_LABEL[shape].toLowerCase()}`}>
          <h3 className="mb-1.5 text-xs font-semibold text-muted">
            Draw on the map: {SHAPE_LABEL[shape].toLowerCase()}
          </h3>
          <ul className="flex flex-col gap-1">
            {traced
              .filter((t) => shapeOf(t) === shape)
              .map((t: AssetType) => (
                <li key={t.code}>
                  <button
                    type="button"
                    aria-pressed={active === t.code}
                    title={t.hint}
                    onClick={() => startDraw(t)}
                    className={cn(
                      'flex w-full items-center gap-2 rounded-lg border border-line-soft px-2 py-1.5 text-left text-[13px] hover:bg-paper-2',
                      active === t.code && 'border-signal bg-signal-soft',
                    )}
                  >
                    <span
                      aria-hidden="true"
                      className="size-3 shrink-0 rounded-sm"
                      style={{ background: t.colourHex }}
                    />
                    {t.label}
                  </button>
                </li>
              ))}
          </ul>
        </section>
      ))}
    </div>
  )
}
