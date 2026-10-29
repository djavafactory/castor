import { DataTable, SectionHeading } from '@/components/ui/card'
import { EmptyState } from '@/components/states/states'
import { useAccuracyReport, useCatalogue } from '@/lib/api'

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/** "27 Oct, 14:05": no year, the browser's own clock, like the History popover's entries. */
function formatRanAt(iso: string): string {
  const d = new Date(iso)
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${day} ${MONTHS[d.getMonth()]}, ${hh}:${mm}`
}

function formatResidual(m: number | null): string {
  return m === null ? 'n/a' : `${m.toFixed(2)} m`
}

function formatRoundTrip(mm: number): string {
  return `${mm.toFixed(3)} mm`
}

/**
 * Right panel section "ACCURACY CHECK" (DESIGNER-11): the result of the last "Verify accuracy" run from the More
 * menu, read from the query cache the menu action filled - this section never asks the server itself.
 */
export function AccuracyCheckSection({ layoutId }: { layoutId: string | undefined }) {
  const { data: report } = useAccuracyReport(layoutId)
  const catalogue = useCatalogue(report !== undefined)

  return (
    <section aria-label="Accuracy check">
      <SectionHeading>Accuracy check</SectionHeading>
      {report === undefined ? (
        <EmptyState className="p-0 py-2 text-left">
          Not run yet. Press Verify accuracy in the More menu.
        </EmptyState>
      ) : (
        <>
          <p className="text-[13px] text-ink">
            {`Run ${formatRanAt(report.ranAt)} against ${plural(report.controlPoints.length, 'control point', 'control points')}.`}
          </p>
          <div className="mt-1.5">
            <DataTable
              caption="Control points"
              rows={report.controlPoints}
              rowKey={(p) => p.code}
              columns={[
                { key: 'point', header: 'Control point', render: (p) => `${p.name} (${p.code})` },
                {
                  key: 'residual',
                  header: 'Residual',
                  numeric: true,
                  render: (p) => formatResidual(p.residualM),
                },
                {
                  key: 'roundTrip',
                  header: 'Round trip',
                  numeric: true,
                  render: (p) => formatRoundTrip(p.roundTripMm),
                },
              ]}
            />
          </div>
          <p className="mt-2 text-xs text-muted">
            {`Largest residual ${report.maxResidualM.toFixed(2)} m between the stored local position and the WGS84 point; largest projection round trip ${report.maxRoundTripMm.toFixed(3)} mm. ${report.note}`}
          </p>
          <ul className="mt-2 flex flex-col gap-1">
            {(catalogue.data?.accuracyClasses ?? []).map((a) => (
              <li key={a.code} className="flex items-center gap-2 text-[13px]">
                <span
                  aria-hidden="true"
                  className="size-3 shrink-0 rounded-sm"
                  style={{ backgroundColor: a.colourHex }}
                />
                <span className="flex-1">{a.label}</span>
                <span className="text-muted">
                  {plural(report.countsByAccuracy[a.code] ?? 0, 'shape', 'shapes')}
                </span>
              </li>
            ))}
          </ul>
          <p className="mt-2 text-xs text-ink">
            {`${plural(report.approximateCount, 'shape has', 'shapes have')} no survey. They carry an Approximate label everywhere they are shown.`}
          </p>
        </>
      )}
    </section>
  )
}
