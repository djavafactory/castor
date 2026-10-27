import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { toast } from 'sonner'
import {
  defaultPortCode,
  queryKeys,
  useCurrentLayout,
  useNewLayoutVersion,
  usePort,
} from '@/lib/api'
import { NEW_VERSION_MESSAGE, NEW_VERSION_TOAST_ID, publishedLabel } from './published-label'

/** The published version as small muted text; the publish note is the tooltip. */
export function PublishedVersion() {
  const { data } = useCurrentLayout()
  const port = usePort()
  if (!data) return null
  const header = data.data.layout
  return (
    <span
      className="text-xs text-muted"
      title={header.note || undefined}
      tabIndex={header.note ? 0 : undefined}
    >
      {publishedLabel(header, port.data?.timeZone)}
    </span>
  )
}

/**
 * Polls the ETag of the published layout and offers a reload when a newer version exists. It never replaces
 * the picture by itself: the user decides when the scene changes. Renders nothing.
 */
export function NewVersionWatcher() {
  const client = useQueryClient()
  const { data } = useCurrentLayout()
  const newer = useNewLayoutVersion(data?.etag ?? null)

  useEffect(() => {
    if (!newer) return
    toast(NEW_VERSION_MESSAGE, {
      id: NEW_VERSION_TOAST_ID,
      duration: Infinity,
      action: {
        label: 'Reload',
        onClick: () => {
          toast.dismiss(NEW_VERSION_TOAST_ID)
          void client.invalidateQueries({ queryKey: queryKeys.current(defaultPortCode()) })
        },
      },
    })
  }, [newer, client])

  useEffect(() => () => void toast.dismiss(NEW_VERSION_TOAST_ID), [])
  return null
}
