import { ApiError } from '@/lib/api-client'

/** What to tell a person about a failed action: the server's own words when it gave any. */
export function problemText(error: unknown): string {
  if (error instanceof ApiError) {
    return error.detail && error.detail !== error.title
      ? `${error.title}: ${error.detail}`
      : error.title
  }
  return 'Something went wrong. Try again.'
}
