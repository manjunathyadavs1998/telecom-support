import type { ProblemDetail } from '@/types/api'

export const GENERIC_ERROR_MESSAGE = 'Something went wrong. Please try again.'

/** Any non-2xx response (or a network failure, `status` 0) from the API client (frontend.md §7 Errors). */
export class ApiError extends Error {
  readonly status: number
  readonly problem: ProblemDetail | null
  readonly correlationId: string | null
  readonly retryAfterSec: number | undefined

  constructor(init: {
    status: number
    problem: ProblemDetail | null
    correlationId: string | null
    retryAfterSec?: number
    cause?: unknown
  }) {
    super(init.problem?.detail ?? init.problem?.title ?? GENERIC_ERROR_MESSAGE, { cause: init.cause })
    this.name = 'ApiError'
    this.status = init.status
    this.problem = init.problem
    this.correlationId = init.correlationId
    this.retryAfterSec = init.retryAfterSec
  }

  get fieldErrors(): NonNullable<ProblemDetail['errors']> {
    return this.problem?.errors ?? []
  }
}

function isProblemDetail(body: unknown): body is ProblemDetail {
  if (typeof body !== 'object' || body === null) return false
  const candidate = body as Record<string, unknown>
  return typeof candidate.status === 'number' && typeof candidate.type === 'string'
}

function parseRetryAfter(value: string | null): number | undefined {
  if (value === null || !/^\s*\d+\s*$/.test(value)) return undefined
  return Number.parseInt(value, 10)
}

/** Builds an `ApiError` from a non-2xx response and the body openapi-fetch parsed (JSON object, or raw text). */
export function toApiError(response: Response, body: unknown): ApiError {
  const problem = isProblemDetail(body) ? body : null
  return new ApiError({
    status: response.status,
    problem,
    correlationId: problem?.correlationId ?? response.headers.get('X-Correlation-Id'),
    retryAfterSec: parseRetryAfter(response.headers.get('Retry-After')),
  })
}

type ServerErrorOption = { type: string; message: string }

/**
 * Maps a server error onto a form (frontend.md §6): known `errors[].field` → that field, everything else → `root.server`.
 * `setError` is react-hook-form's `setError` (structurally typed here).
 */
export function applyProblemToForm<F extends string>(
  error: unknown,
  setError: (name: F | 'root.server', error: ServerErrorOption) => void,
  fields: readonly F[],
): void {
  if (!(error instanceof ApiError)) {
    setError('root.server', { type: 'server', message: GENERIC_ERROR_MESSAGE })
    return
  }
  const known = new Set<string>(fields)
  let unmapped = error.fieldErrors.length === 0
  for (const violation of error.fieldErrors) {
    if (known.has(violation.field)) {
      setError(violation.field as F, { type: 'server', message: violation.message })
    } else {
      unmapped = true
    }
  }
  if (unmapped) setError('root.server', { type: 'server', message: error.message })
}
