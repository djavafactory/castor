import { apiRequest } from '@/lib/api-client'
import { importReportSchema, type ImportReport } from './schemas'
import { parseResponse } from './validated'

/** GeoJSON export and import of a layout version (DESIGNER-10). */

export type GeoJsonFile = { filename: string; text: string; featureCount: number }

/** The version as a GeoJSON file: its name from the server, the text to save and how many features it holds. */
export async function exportGeoJson(layoutId: string, signal?: AbortSignal): Promise<GeoJsonFile> {
  const response = await apiRequest<{ features?: unknown[] }>(
    `/v1/layouts/${encodeURIComponent(layoutId)}/export`,
    { headers: { Accept: 'application/geo+json' }, signal, timeoutMs: 60_000 },
  )
  return {
    filename: response.filename ?? 'castor-layout.geojson',
    text: JSON.stringify(response.data, null, 2),
    featureCount: response.data.features?.length ?? 0,
  }
}

/**
 * Sends a file to the draft. A dry run stores nothing and returns the report the real import would give; the real one
 * stores everything accepted in one transaction.
 */
export async function importGeoJson(
  layoutId: string,
  file: File,
  dryRun: boolean,
  signal?: AbortSignal,
): Promise<ImportReport> {
  const form = new FormData()
  form.append('file', file, file.name)
  const response = await apiRequest<unknown>(`/v1/layouts/${encodeURIComponent(layoutId)}/import`, {
    method: 'POST',
    body: form,
    query: { dryRun },
    signal,
    timeoutMs: 120_000,
  })
  return parseResponse('POST import', importReportSchema, response.data)
}
