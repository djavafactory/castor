import { useAssetTypes } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { useEditorStore } from '@/lib/stores/editor-store'
import {
  CROSSES_READOUT,
  formatCoordinate,
  shapeOf,
  sketchStats,
  type DrawShape,
} from './draw-rules'

/** The floating readout of the draw tool: counts, last vertex, real measurements and the three buttons. */
export function DrawReadout({ onFinish }: { onFinish: () => void }) {
  const tool = useEditorStore((s) => s.tool)
  const types = useAssetTypes()
  const removeLastPoint = useEditorStore((s) => s.removeLastPoint)
  const clearTool = useEditorStore((s) => s.clearTool)
  if (tool?.kind !== 'draw') return null
  const type = types.data?.find((t) => t.code === tool.typeCode)
  const shape = type ? shapeOf(type) : null
  // A point type is placed by one click; there is nothing to measure or finish
  if (!type || (shape !== 'polygon' && shape !== 'line')) return null

  const stats = sketchStats(shape as DrawShape, tool.points)
  const last = tool.points.at(-1)
  return (
    <section
      role="group"
      aria-label={`Drawing: ${type.label}`}
      data-testid="draw-readout"
      className="absolute top-16 left-3 z-overlay w-64 rounded-xl border border-line bg-paper p-3 text-xs text-ink shadow-popover"
    >
      <h3 className="mb-1.5 text-[13px] font-semibold">Drawing: {type.label}</h3>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 font-mono">
        <dt className="text-muted">Vertices</dt>
        <dd className="text-right">{stats.vertices}</dd>
        <dt className="text-muted">Last vertex</dt>
        <dd className="text-right">{last ? formatCoordinate(last) : '-'}</dd>
        <dt className="text-muted">{shape === 'polygon' ? 'Perimeter' : 'Length'}</dt>
        <dd className="text-right">{stats.size.toFixed(1)} m</dd>
        {stats.area !== null && (
          <>
            <dt className="text-muted">Area</dt>
            <dd className="text-right">{Math.round(stats.area)} m2</dd>
          </>
        )}
      </dl>
      {stats.crossing ? (
        <p role="alert" className="mt-1.5 text-bad">
          {CROSSES_READOUT}
        </p>
      ) : (
        <p className="mt-1.5 text-muted">{type.hint}</p>
      )}
      <div className="mt-2 flex flex-wrap gap-1.5">
        <Button size="sm" variant="primary" onClick={onFinish}>
          Finish (Enter)
        </Button>
        <Button size="sm" onClick={removeLastPoint} disabled={stats.vertices === 0}>
          Undo point
        </Button>
        <Button size="sm" variant="ghost" onClick={clearTool}>
          Cancel (Esc)
        </Button>
      </div>
    </section>
  )
}
