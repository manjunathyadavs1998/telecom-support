import { skipToken, useQuery, type QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/services/api/errors'
import { LOOKUP_TOKEN_TTL_MS, useInboundSessionStore } from '@/stores/inboundSessionStore'
import type { CustomerMatch, IdentityCheck } from '@/types/api'

/**
 * Inbound verification state and query keys (03-inbound/frontend.md §3.1–§3.3, §4, §5; INB-01.2, NFR-PRIV). The MSISDN is never
 * part of a key.
 */

type PageParams = Readonly<Record<string, unknown>>

const ROOT = 'inbound'

export const inboundKeys = {
  all: [ROOT] as const,
  customer: (id: number) => [ROOT, 'customer', id] as const,
  match: (id: number) => [ROOT, 'customer', id, 'match'] as const,
  check: (id: number) => [ROOT, 'customer', id, 'check'] as const,
  profile: (id: number) => [ROOT, 'customer', id, 'profile'] as const,
  subscriptions: (id: number) => [ROOT, 'customer', id, 'subscriptions'] as const,
  invoices: (id: number) => [ROOT, 'customer', id, 'invoices'] as const,
  payments: (id: number, params: PageParams) => [ROOT, 'customer', id, ['payments', params]] as const,
  cases: (id: number, params: PageParams) => [ROOT, 'customer', id, ['cases', params]] as const,
  case: (id: number, reference: string) => [ROOT, 'customer', id, ['case', reference]] as const,
  eligibility: (id: number) => [ROOT, 'customer', id, 'eligibility'] as const,
  reactivations: (id: number, params: PageParams) => [ROOT, 'customer', id, ['reactivations', params]] as const,
  categories: () => [ROOT, 'categories'] as const,
}

/** The customer's keys that are not protected data: the masked search match and the check itself. */
const UNPROTECTED = new Set(['match', 'check'])

/** The search match without its token (§3.1): the token lives only in `inboundSessionStore`. */
export type CachedMatch = Omit<CustomerMatch, 'lookupToken'>

/** The identity check as cached (§3.3 F6): with the server − client clock offset read from the response `Date` header. */
export type CachedCheck = IdentityCheck & { serverOffsetMs: number }

/** `server − client` in ms from a response's `Date` header; 0 when absent or unparsable (§3.3). */
export function serverOffsetOf(response: Response, now: number = Date.now()): number {
  const date = response.headers.get('Date')
  const server = date === null ? Number.NaN : Date.parse(date)
  return Number.isFinite(server) ? server - now : 0
}

/** The server's current time for a cached check (§3.3): `Date.now() + offset`. */
export function serverNow(check: Pick<CachedCheck, 'serverOffsetMs'> | null | undefined): number {
  return Date.now() + (check?.serverOffsetMs ?? 0)
}

/**
 * A search hit (§3.1, §3.2): the match minus its token → the Query cache (`staleTime: Infinity`, `gcTime` 15 min via
 * `useCustomerMatch`), the token → the memory-only store.
 */
export function rememberMatch(queryClient: QueryClient, { lookupToken, ...match }: CustomerMatch): void {
  useInboundSessionStore.getState().setToken(match.customerId, lookupToken)
  queryClient.setQueryData<CachedMatch>(inboundKeys.match(match.customerId), match)
}

/** The cached search match for `customerId` (header before verification); never fetched (`skipToken`). */
export function useCustomerMatch(customerId: number) {
  return useQuery<CachedMatch>({
    queryKey: inboundKeys.match(customerId),
    queryFn: skipToken,
    staleTime: Number.POSITIVE_INFINITY,
    gcTime: LOOKUP_TOKEN_TTL_MS,
  })
}

/** An inbound 403 whose problem `type` ends with `verification-required` (backend R6): the check is gone or expired. */
export function isVerificationRequired(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403 && (error.problem?.type.endsWith('verification-required') ?? false)
}

/**
 * Verification lost (§3.3): the check becomes `null` and every protected query of that customer is removed (no stale PII). The
 * workspace frame reacts to the `null` check by opening `ReverifyDialog`; the match and the token are kept.
 */
export function markVerificationLost(queryClient: QueryClient, customerId: number): void {
  queryClient.setQueryData<CachedCheck | null>(inboundKeys.check(customerId), null)
  queryClient.removeQueries({
    queryKey: inboundKeys.customer(customerId),
    predicate: (query) => {
      const part = query.queryKey[3]
      return !(typeof part === 'string' && UNPROTECTED.has(part))
    },
  })
}
