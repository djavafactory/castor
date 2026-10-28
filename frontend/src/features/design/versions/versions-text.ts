import type { LayoutDiff } from '@/lib/api'

export const DISCARD_PROMPT = 'Discard the draft? All unpublished changes are lost.'
export const BLANK_PROMPT = 'Start a blank draft? The current draft is discarded.'
export const BLANK_MESSAGE =
  'Blank layout on the satellite basemap. Search, place and draw, then publish.'
export const NOTE_PLACEHOLDER = 'What changed'
export const NOT_A_PUBLISHER_TIP = 'Only publishers can publish'
export const PUBLISH_TIP = 'Validate and publish an immutable version'

export const publishBlockedMessage = (errors: number) =>
  `Publishing blocked: ${errors} error(s). Fix them first.`
export const publishedMessage = (versionNo: number) =>
  `Version ${versionNo} published. Monitor shows it now.`
export const restorePrompt = (versionNo: number) =>
  `Load version ${versionNo} into a new draft? Asset IDs stay the same.`
export const restoredMessage = (versionNo: number) =>
  `Version ${versionNo} loaded. Asset IDs are unchanged. Publish to make it live.`

/** "Blank draft" or "Draft (based on v3)": the text of the status badge. */
export const draftStatusText = (basedOnVersionNo: number | null | undefined) =>
  basedOnVersionNo == null ? 'Blank draft' : `Draft (based on v${basedOnVersionNo})`

export const assetsText = (n: number) => `${n} ${n === 1 ? 'asset' : 'assets'}`

/** "2 added, 1 changed, 0 removed" */
export function diffSummary(diff: LayoutDiff): string {
  return `${diff.added.length} added, ${diff.changed.length} changed, ${diff.removed.length} removed`
}

/** "28 Oct, 10:35" in the time zone of the port. */
export function formatChangeTime(iso: string, timeZone?: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-GB', {
      timeZone,
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    })
      .formatToParts(date)
      .map((p) => [p.type, p.value]),
  )
  return `${parts.day} ${parts.month}, ${parts.hour}:${parts.minute}`
}
