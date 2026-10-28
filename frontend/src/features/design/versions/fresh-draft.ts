import type { QueryClient } from '@tanstack/react-query'
import { createDraft, defaultPortCode, queryKeys } from '@/lib/api'

/**
 * After a publish or a discard the port has no draft. Design needs one to edit, so a new one is started from the
 * published layout at once. If that fails the next entry to Design creates it.
 */
export async function startFreshDraft(client: QueryClient): Promise<void> {
  const code = defaultPortCode()
  try {
    const draft = await createDraft(code, 'published')
    client.setQueryData(queryKeys.draft(code), draft)
  } catch {
    client.removeQueries({ queryKey: queryKeys.draft(code), exact: true })
  }
  await client.invalidateQueries({ queryKey: queryKeys.versions(code) })
}
