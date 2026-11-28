import { z } from 'zod'
import type { ReactivationRequest } from '@/types/api'

export const REASON_MIN = 10
export const REASON_MAX = 500
export const APPROVAL_MAX = 64
/** The server default when `hours` is omitted (contract I14): min(24, maxHours). */
export const SERVER_DEFAULT_HOURS = 24

export const REACTIVATION_FIELDS = ['hours', 'reason', 'approvalReference'] as const
export type ReactivationField = (typeof REACTIVATION_FIELDS)[number]

export type ReactivationInput = { hours: string; reason: string; approvalReference: string }

export const reactivationDefaults = (maxHours: number): ReactivationInput => ({
  hours: String(maxHours),
  reason: '',
  approvalReference: '',
})

/**
 * 03-inbound/frontend.md §6 Reactivation: hours int 1..maxHours (default maxHours) · reason trim 10–500 · approval reference trim
 * ≤ 64, `''` → null. `hours` is sent only when it differs from what the server would pick anyway (min(24, maxHours)), so the
 * request always restores exactly the hours shown.
 */
export function reactivationSchema(maxHours: number) {
  return z
    .object({
      hours: z
        .string()
        .trim()
        .regex(/^\d+$/, `Enter whole hours from 1 to ${maxHours}.`)
        .transform(Number)
        .refine((value) => value >= 1 && value <= maxHours, `At most ${maxHours} hours`),
      reason: z
        .string()
        .trim()
        .min(REASON_MIN, `Enter at least ${REASON_MIN} characters.`)
        .max(REASON_MAX, `Use at most ${REASON_MAX} characters.`),
      approvalReference: z.string().trim().max(APPROVAL_MAX, `Use at most ${APPROVAL_MAX} characters.`),
    })
    .transform(
      (values): ReactivationRequest => ({
        ...(values.hours === Math.min(SERVER_DEFAULT_HOURS, maxHours) ? {} : { hours: values.hours }),
        reason: values.reason,
        approvalReference: values.approvalReference === '' ? null : values.approvalReference,
      }),
    )
}
