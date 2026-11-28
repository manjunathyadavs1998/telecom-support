import { z } from 'zod'
import type { CaseStatus, ClosureType } from '@/types/api'

export const REMARKS_MIN = 10
export const REMARKS_MAX = 1000
/** The verification window (backend OQ 4): a display hint only, the server decides (§3.3, OQ 8). */
export const VERIFICATION_WINDOW_MS = 3 * 24 * 60 * 60 * 1000

/** The form's values: plain strings ('' = not chosen) until the schema narrows them. */
export type StatusInput = { toStatus: string; closureType: string; remarks: string }

/** Remarks are required for Resolved, Reopened and Closed by customer confirmation (R7, OUT-05.2). */
export function remarksRequired(to: string, closureType: string): boolean {
  return to === 'RESOLVED' || to === 'REOPENED' || (to === 'CLOSED' && closureType === 'CUSTOMER_CONFIRMED')
}

/** The confirm step (XC-06, OQ 6): Resolved, Closed and Reopened only. */
export function highRisk(to: string): boolean {
  return to === 'RESOLVED' || to === 'CLOSED' || to === 'REOPENED'
}

/**
 * The status form (04-outbound/frontend.md §6): `toStatus` ∈ the allowed transitions; `closureType` iff CLOSED; remarks trim ≤ 1000,
 * required 10–1000 when {@link remarksRequired}. The output is the O8 body (without `version`, added at submit time).
 */
export function statusSchema(allowed: readonly CaseStatus[]) {
  return z
    .object({
      toStatus: z.string(),
      closureType: z.string(),
      remarks: z.string().trim().max(REMARKS_MAX, `Use at most ${REMARKS_MAX} characters.`),
    })
    .superRefine((v, ctx) => {
      if (!allowed.includes(v.toStatus as CaseStatus)) {
        ctx.addIssue({ code: 'custom', path: ['toStatus'], message: 'Choose the new status.' })
      }
      if (v.toStatus === 'CLOSED' && v.closureType === '') {
        ctx.addIssue({ code: 'custom', path: ['closureType'], message: 'Choose how the closure was confirmed.' })
      }
      if (remarksRequired(v.toStatus, v.closureType) && v.remarks.length < REMARKS_MIN) {
        ctx.addIssue({ code: 'custom', path: ['remarks'], message: `Enter at least ${REMARKS_MIN} characters.` })
      }
    })
    .transform((v) => ({
      toStatus: v.toStatus as CaseStatus,
      closureType: v.toStatus === 'CLOSED' ? (v.closureType as ClosureType) : null,
      remarks: v.remarks === '' ? null : v.remarks,
    }))
}
