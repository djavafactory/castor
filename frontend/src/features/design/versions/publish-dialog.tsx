import * as AlertDialog from '@radix-ui/react-alert-dialog'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Label, Select } from '@/components/ui/field'
import type { Reviewer } from '@/lib/api'

interface PublishDialogProps {
  open: boolean
  /** "2 added, 1 changed, 0 removed" */
  summary: string
  note: string
  reviewers: readonly Reviewer[]
  onConfirm: (reviewedBy: string | null) => void
  onCancel: () => void
}

/** The last question before a version goes live: what changes, the note and, optionally, who reviewed it. */
export function PublishDialog({
  open,
  summary,
  note,
  reviewers,
  onConfirm,
  onCancel,
}: PublishDialogProps) {
  const [reviewer, setReviewer] = useState('')
  return (
    <AlertDialog.Root open={open} onOpenChange={(o) => !o && onCancel()}>
      <AlertDialog.Portal>
        <AlertDialog.Overlay className="fixed inset-0 z-menu bg-ink/30" />
        <AlertDialog.Content className="fixed top-1/2 left-1/2 z-menu w-[380px] max-w-[calc(100vw-2rem)] -translate-x-1/2 -translate-y-1/2 rounded-xl border border-line bg-paper p-4 shadow-popover">
          <AlertDialog.Title className="text-base font-bold">Publish this draft?</AlertDialog.Title>
          <AlertDialog.Description className="mt-1.5 text-[13px] text-muted">
            It becomes the version Monitor shows and cannot be changed afterwards.
          </AlertDialog.Description>
          <dl className="mt-3 grid grid-cols-[80px_1fr] gap-x-2 gap-y-1 text-[13px]">
            <dt className="text-xs text-muted">Changes</dt>
            <dd className="m-0">{summary}</dd>
            <dt className="text-xs text-muted">Note</dt>
            <dd className="m-0 break-words">{note === '' ? '-' : note}</dd>
          </dl>
          <div className="mt-3 flex flex-col gap-1">
            <Label htmlFor="publish-reviewer">Reviewed by (optional)</Label>
            <Select
              id="publish-reviewer"
              value={reviewer}
              onChange={(e) => setReviewer(e.target.value)}
            >
              <option value="">Nobody</option>
              {reviewers.map((r) => (
                <option key={r.username} value={r.username}>
                  {r.displayName === r.username ? r.username : `${r.displayName} (${r.username})`}
                </option>
              ))}
            </Select>
          </div>
          <div className="mt-4 flex justify-end gap-2">
            <AlertDialog.Cancel asChild>
              <Button>Cancel</Button>
            </AlertDialog.Cancel>
            <AlertDialog.Action asChild>
              <Button
                variant="primary"
                onClick={() => onConfirm(reviewer === '' ? null : reviewer)}
              >
                Publish
              </Button>
            </AlertDialog.Action>
          </div>
        </AlertDialog.Content>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  )
}
