import { useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, SectionHeading } from '@/components/ui/card'
import { importGeoJson, type ImportReport } from '@/lib/api'
import { canDesign } from '@/lib/roles'
import { useAuthStore } from '@/lib/stores/auth-store'
import {
  EXCHANGE_HELP,
  SKIP_REASONS_SHOWN,
  importedMessage,
  reportCounts,
  reportRoundTrip,
  reportTitle,
} from './exchange-text'
import { downloadExport, problemText } from './download-export'
import { importIntoDraft } from './import-geojson'

/**
 * "GeoJSON" section of the Versions tab: export of the draft, and import of a file that is first tried as a dry run so
 * the editor sees what would be added, updated and skipped, and how well the coordinates survive, before anything is
 * stored.
 */
export function GeoJsonExchange({
  draftId,
  disabled,
}: {
  draftId: string | undefined
  disabled: boolean
}) {
  const client = useQueryClient()
  const editor = canDesign(useAuthStore((s) => s.roles))
  const input = useRef<HTMLInputElement>(null)
  const [pending, setPending] = useState<{ file: File; report: ImportReport } | null>(null)
  const [busy, setBusy] = useState(false)
  const [showAll, setShowAll] = useState(false)

  function reset() {
    setPending(null)
    setShowAll(false)
    if (input.current) input.current.value = ''
  }

  async function onChoose(file: File | undefined) {
    if (!file || !draftId) return
    setBusy(true)
    try {
      setPending({ file, report: await importGeoJson(draftId, file, true) })
      setShowAll(false)
    } catch (error) {
      toast.error(problemText(error))
      reset()
    } finally {
      setBusy(false)
    }
  }

  async function onImport() {
    if (!pending || !draftId) return
    setBusy(true)
    try {
      const report = await importIntoDraft(client, draftId, pending.file)
      toast(importedMessage(report.added + report.updated, report.skipped))
      reset()
    } catch (error) {
      toast.error(problemText(error))
    } finally {
      setBusy(false)
    }
  }

  const reasons = pending?.report.skipReasons ?? []
  const nothing = pending !== null && pending.report.added + pending.report.updated === 0

  return (
    <>
      <SectionHeading>GeoJSON</SectionHeading>
      <p className="text-xs text-muted">{EXCHANGE_HELP}</p>
      <div className="mt-2 flex flex-wrap gap-2">
        <Button
          disabled={!draftId || disabled || busy}
          onClick={() => draftId && void downloadExport(draftId)}
        >
          Export GeoJSON
        </Button>
        {editor && (
          <>
            <Button
              disabled={!draftId || disabled || busy || pending !== null}
              onClick={() => input.current?.click()}
            >
              Import GeoJSON
            </Button>
            <input
              ref={input}
              type="file"
              accept=".geojson,.json"
              aria-label="GeoJSON file"
              className="hidden"
              onChange={(e) => void onChoose(e.target.files?.[0])}
            />
          </>
        )}
      </div>

      {pending && (
        <Card
          className="mt-3 flex flex-col gap-1.5 p-3 text-[13px]"
          role="region"
          aria-label="Import report"
        >
          <h4 className="text-xs font-semibold">{reportTitle(pending.report.file)}</h4>
          <p className="m-0">{reportCounts(pending.report)}</p>
          <p className="m-0 text-muted">{reportRoundTrip(pending.report)}</p>
          {reasons.length > 0 && (
            <>
              <ul className="m-0 flex list-disc flex-col gap-0.5 pl-4 text-xs">
                {(showAll ? reasons : reasons.slice(0, SKIP_REASONS_SHOWN)).map((reason, i) => (
                  <li key={i}>{reason}</li>
                ))}
              </ul>
              {reasons.length > SKIP_REASONS_SHOWN && (
                <button
                  type="button"
                  className="self-start text-xs text-signal underline"
                  aria-expanded={showAll}
                  onClick={() => setShowAll((v) => !v)}
                >
                  {showAll ? 'Show fewer' : `Show all ${reasons.length}`}
                </button>
              )}
            </>
          )}
          <div className="mt-1 flex gap-2">
            <Button
              variant="primary"
              disabled={busy || nothing}
              title={nothing ? 'Nothing in this file can be imported' : undefined}
              onClick={() => void onImport()}
            >
              Import
            </Button>
            <Button disabled={busy} onClick={reset}>
              Cancel
            </Button>
          </div>
        </Card>
      )}
    </>
  )
}
