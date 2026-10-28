import { toast } from 'sonner'
import { queryClient } from '@/app/query-client'
import { fetchValidation, queryKeys, defaultPortCode, type Layout } from '@/lib/api'
import { waitForSaves } from '@/features/shell/leave-design'

const SAVE_WAIT_MS = 10_000

/** Every edit is saved to the server draft as it is made, so "save" waits for those and then runs the checks. */
export async function checkAndSave(): Promise<void> {
  await waitForSaves(SAVE_WAIT_MS)
  const draft = queryClient.getQueryData<Layout>(queryKeys.draft(defaultPortCode()))
  if (!draft) {
    toast('There is no draft to check.')
    return
  }
  try {
    const report = await fetchValidation(draft.versionId)
    await queryClient.invalidateQueries({ queryKey: ['validation'] })
    if (report.errors > 0) {
      toast.error(`Draft saved. ${report.errors} error(s) block publishing; see Issues.`)
    } else if (report.warnings > 0) {
      toast(`Draft saved. No errors, ${report.warnings} warning(s).`)
    } else {
      toast('Draft saved. No issues.')
    }
  } catch {
    toast.error('Draft saved, but the check could not run. Try again.')
  }
}
