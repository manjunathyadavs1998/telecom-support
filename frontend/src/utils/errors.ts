import { ApiError } from '@/services/http'

/** Human-readable message for any thrown value (ApiError carries the backend ProblemDetail detail). */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return 'Something went wrong.'
}
