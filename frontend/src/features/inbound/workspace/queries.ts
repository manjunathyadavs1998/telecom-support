import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { createContext, useContext } from 'react'
import { api, apiClient } from '@/services/api/client'
import { ApiError } from '@/services/api/errors'
import type { AgentCustomerProfile } from '@/types/api'
import { inboundKeys, isVerificationRequired, markVerificationLost, serverOffsetOf, type CachedCheck } from '../verificationState'

/** Warning tone, one polite announcement and "Verify again" from here (§3.3). */
export const WARN_AT_MINUTES = 5

/** Whole minutes left until `validUntil` against the server clock; 0 once elapsed. */
export function minutesLeft(validUntil: string, serverNow: () => number): number {
  const ms = Date.parse(validUntil) - serverNow()
  return Number.isFinite(ms) && ms > 0 ? Math.ceil(ms / 60_000) : 0
}

/** Lists and views of a verified customer (03-inbound/frontend.md §5): 30 s. */
export const INBOUND_STALE_TIME_MS = 30_000

/**
 * I4 `GET …/identity-checks/current` (§3.3): this agent's valid check for the customer, or `null` on 404 (none / expired).
 * The response `Date` header gives the server clock offset (F6).
 */
export async function fetchCurrentCheck(customerId: number): Promise<CachedCheck | null> {
  const result = await apiClient.GET('/api/v1/agent/customers/{customerId}/identity-checks/current', {
    params: { path: { customerId } },
  })
  if (result.response.status === 404) return null
  const check = await api.call(Promise.resolve(result))
  return { ...check, serverOffsetMs: serverOffsetOf(result.response) }
}

/** The check gate (§3.3): `staleTime` 0; the frame calls it first, before any protected query. */
export function useCurrentCheckQuery(customerId: number) {
  return useQuery({
    queryKey: inboundKeys.check(customerId),
    queryFn: () => fetchCurrentCheck(customerId),
    staleTime: 0,
    refetchOnWindowFocus: false,
  })
}

/**
 * Runs a protected request (I5–I15); a 403 `verification-required` marks the verification lost (§3.3) before it rethrows, so
 * the frame opens `ReverifyDialog` and no stale PII stays cached. Every inbound query/mutation goes through this.
 */
export async function withVerification<T>(queryClient: QueryClient, customerId: number, request: () => Promise<T>): Promise<T> {
  try {
    return await request()
  } catch (error) {
    if (isVerificationRequired(error)) markVerificationLost(queryClient, customerId)
    throw error
  }
}

/** I5 profile (INB-01.3): header after verification and the Overview profile section. */
export function useAgentProfileQuery(customerId: number, enabled: boolean) {
  const queryClient = useQueryClient()
  return useQuery<AgentCustomerProfile, ApiError>({
    queryKey: inboundKeys.profile(customerId),
    queryFn: () =>
      withVerification(queryClient, customerId, () =>
        api.call(apiClient.GET('/api/v1/agent/customers/{customerId}/profile', { params: { path: { customerId } } })),
      ),
    enabled,
    staleTime: INBOUND_STALE_TIME_MS,
  })
}

/** What every workspace page reads from the frame (§4 `useVerifiedCustomer()`). */
export type VerifiedCustomer = {
  customerId: number
  /** True while the cached check is VERIFIED: protected queries are `enabled` only then. */
  verified: boolean
  /** The server's current time (ms), from the check's clock offset. */
  serverNow: () => number
  /** Increments after each successful re-verification; a pending form shows "Verified again. Submit when ready." */
  reverifiedCount: number
  /** A form with unsaved input registers it, so "Cancel" in `ReverifyDialog` warns first (§3.3). */
  setDirty: (key: string, dirty: boolean) => void
}

export const VerifiedCustomerContext = createContext<VerifiedCustomer | null>(null)

/** The frame's state; only valid under the `inbound-customer` layout route. */
export function useVerifiedCustomer(): VerifiedCustomer {
  const value = useContext(VerifiedCustomerContext)
  if (value === null) throw new Error('useVerifiedCustomer must be used under WorkspaceFrame')
  return value
}
