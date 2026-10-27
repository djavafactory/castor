import type { QueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ApiError } from '@/lib/api-client'
import { createDraft, fetchDraftLayout, queryKeys, type Layout } from '@/lib/api'

export const editingDraftMessage = (basedOn: number | null | undefined) =>
  basedOn == null
    ? "Design mode: editing your team's blank draft."
    : `Design mode: editing your team's draft (based on v${basedOn}).`

export const draftCreatedMessage = (basedOn: number | null | undefined) =>
  `Design mode: new draft created from published v${basedOn ?? '?'}.`

const isStatus = (error: unknown, status: number) =>
  error instanceof ApiError && error.status === status

let inFlight: Promise<Layout> | null = null

/**
 * Loads the one server draft of the port for an editor entering Design. When there is none a draft is created from the
 * published layout; if someone else created it a moment earlier (409) theirs is loaded. Calls that overlap share one
 * run, so entering twice never creates two drafts. Throws when the draft cannot be loaded or created.
 */
export function enterDesign(client: QueryClient, code: string): Promise<Layout> {
  inFlight ??= load(client, code).finally(() => {
    inFlight = null
  })
  return inFlight
}

function loadDraft(client: QueryClient, code: string): Promise<Layout> {
  return client.fetchQuery({
    queryKey: queryKeys.draft(code),
    queryFn: ({ signal }) => fetchDraftLayout(code, signal),
    staleTime: 0,
  })
}

async function load(client: QueryClient, code: string): Promise<Layout> {
  try {
    const draft = await loadDraft(client, code)
    toast(editingDraftMessage(draft.basedOnVersionNo))
    return draft
  } catch (error) {
    if (!isStatus(error, 404)) throw error
  }
  try {
    const draft = await createDraft(code, 'published')
    client.setQueryData<Layout>(queryKeys.draft(code), draft)
    toast(draftCreatedMessage(draft.basedOnVersionNo))
    void client.invalidateQueries({ queryKey: queryKeys.versions(code) })
    return draft
  } catch (error) {
    if (!isStatus(error, 409)) throw error
    const draft = await loadDraft(client, code)
    toast(editingDraftMessage(draft.basedOnVersionNo))
    return draft
  }
}
