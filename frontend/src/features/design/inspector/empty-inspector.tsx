import { KeyValue, SectionHeading } from '@/components/ui/card'
import { useDraftValidation } from '@/features/design/issues/use-draft-validation'
import { useDraftLayout, usePort } from '@/lib/api'

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

/** Nothing selected in Design: what the layout holds and how to work on it. */
export function EmptyInspector() {
  const draft = useDraftLayout()
  const port = usePort()
  const { query } = useDraftValidation()
  const errors = query.data?.errors
  const warnings = query.data?.warnings
  return (
    <div>
      <p className="m-0 text-[13px] text-muted">
        Select a shape on the map or in 3D, or pick a template from the palette.
      </p>
      <SectionHeading>Layout</SectionHeading>
      <KeyValue
        items={[
          ['Name', port.data?.name ?? '—'],
          ['Assets', String(draft.data?.assets.length ?? 0)],
          [
            'Issues',
            errors === undefined || warnings === undefined
              ? '—'
              : `${plural(errors, 'error', 'errors')}, ${plural(warnings, 'warning', 'warnings')}`,
          ],
          ['Stored CRS', 'WGS84 lon/lat (EPSG:4326)'],
          ['Measured in', `metres (EPSG:${port.data?.measureSrid ?? 3380})`],
        ]}
      />
      <SectionHeading>How to</SectionHeading>
      <ul className="m-0 grid list-disc gap-1.5 pl-4 text-xs text-muted">
        <li>
          <strong className="font-semibold text-ink">Place:</strong> pick a template in the Assets
          palette and click the map. R turns the ghost, Esc cancels.
        </li>
        <li>
          <strong className="font-semibold text-ink">Draw:</strong> pick an outline or line
          template, click its corners on the 2D map and press Enter to finish.
        </li>
        <li>
          <strong className="font-semibold text-ink">Edit:</strong> click a shape to select it. Drag
          its handles to move, resize or rotate it, or type the values in this panel.
        </li>
        <li>
          <strong className="font-semibold text-ink">Shortcuts:</strong> 1-5 camera presets, Ctrl+Z
          and Ctrl+Y undo and redo, Ctrl+S check and save, Delete removes the selection.
        </li>
      </ul>
    </div>
  )
}
