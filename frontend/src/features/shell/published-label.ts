import type { LayoutHeader } from '@/lib/api'

export const NEW_VERSION_TOAST_ID = 'new-layout-version'
export const NEW_VERSION_MESSAGE = 'A new layout version is published.'

/** "27 Oct 2026 14:05" in the time zone of the port, so everyone reads the same clock. */
export function formatPublishedAt(iso: string, timeZone?: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-GB', {
      timeZone,
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    })
      .formatToParts(date)
      .map((p) => [p.type, p.value]),
  )
  return `${parts.day} ${parts.month} ${parts.year} ${parts.hour}:${parts.minute}`
}

/** "Published v3 · 27 Oct 2026 14:05 by ada" */
export function publishedLabel(layout: LayoutHeader, timeZone?: string): string {
  const when = layout.publishedAt ? ` · ${formatPublishedAt(layout.publishedAt, timeZone)}` : ''
  const by = layout.publishedBy ? ` by ${layout.publishedBy}` : ''
  return `Published v${layout.versionNo}${when}${by}`
}
