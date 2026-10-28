import type { ImportReport } from '@/lib/api'

export const EXCHANGE_HELP =
  'Export writes every shape as [longitude, latitude] with its provenance. Import adds or updates catalogue assets; shapes that overlap, cross themselves or fall in a restricted zone are skipped.'

/** How many skip reasons the report card shows before "Show all". */
export const SKIP_REASONS_SHOWN = 8

export const exportedMessage = (n: number) => `Exported ${n} features as GeoJSON`
export const importedMessage = (n: number, skipped: number) =>
  `${n} features imported, ${skipped} skipped`
export const importedHistory = (n: number, file: string) => `Imported ${n} features from ${file}`

export const reportTitle = (file: string) => `Import of ${file}`
export const reportCounts = (r: ImportReport) =>
  `${r.added} added, ${r.updated} updated, ${r.skipped} skipped`
export const reportRoundTrip = (r: ImportReport) =>
  `Round trip: ${r.roundTrip.coordinates} coordinates, largest difference ${r.roundTrip.maxDifferenceMm.toFixed(3)} mm`
