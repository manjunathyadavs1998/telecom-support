import { useMutation } from '@tanstack/react-query'
import { OWN_FORBIDDEN_HANDLING } from '@/app/queryClient'
import { api, apiClient } from '@/services/api/client'
import type { IdentityCheckStart } from '@/types/api'
import { serverOffsetOf, type CachedCheck } from '../verificationState'

/**
 * I2 `POST /agent/customers/{id}/identity-checks` (INB-01.2): OTP → 201 PENDING (code sent to the primary mobile); KBA → 201
 * VERIFIED. The response `Date` header gives the server clock offset (§3.3 F6).
 */
export async function startIdentityCheck(customerId: number, body: IdentityCheckStart): Promise<CachedCheck> {
  const result = await apiClient.POST('/api/v1/agent/customers/{customerId}/identity-checks', {
    params: { path: { customerId } },
    body,
  })
  const check = await api.call(Promise.resolve(result))
  return { ...check, serverOffsetMs: serverOffsetOf(result.response) }
}

/** I3 `POST …/identity-checks/{checkId}/confirm` → 200 VERIFIED. */
export async function confirmIdentityCheck(customerId: number, checkId: number, code: string): Promise<CachedCheck> {
  const result = await apiClient.POST('/api/v1/agent/customers/{customerId}/identity-checks/{checkId}/confirm', {
    params: { path: { customerId, checkId } },
    body: { code },
  })
  const check = await api.call(Promise.resolve(result))
  return { ...check, serverOffsetMs: serverOffsetOf(result.response) }
}

/**
 * Start (OTP send or KBA answers). `gcTime: 0`: the token and answers leave the MutationCache at once (NFR-SEC). A 403 is shown
 * by the panel itself (§6: "You don't have permission to do that."), so no global toast.
 */
export function useStartIdentityCheck(customerId: number) {
  return useMutation({
    mutationFn: (body: IdentityCheckStart) => startIdentityCheck(customerId, body),
    gcTime: 0,
    meta: OWN_FORBIDDEN_HANDLING,
  })
}

/** Confirm an OTP code; `gcTime: 0` so the code never stays in the MutationCache (NFR-SEC). */
export function useConfirmIdentityCheck(customerId: number) {
  return useMutation({
    mutationFn: ({ checkId, code }: { checkId: number; code: string }) => confirmIdentityCheck(customerId, checkId, code),
    gcTime: 0,
    meta: OWN_FORBIDDEN_HANDLING,
  })
}
