import { useEffect, useRef, useState } from 'react'
import { z } from 'zod'
import { normaliseMsisdn } from '@/features/customer/profile/contactSchemas'
import { ApiError } from '@/services/api/errors'
import type { InboundVerificationProblem } from '@/types/api'

/** OTP code (03-inbound/frontend.md §6): exactly six digits. */
export const otpSchema = z.object({
  code: z.string().trim().regex(/^\d{6}$/, 'Enter the 6-digit code.'),
})
export type OtpInput = z.input<typeof otpSchema>

/** An email is lower-cased; anything else is a phone number (10 digits → `+91…`, OQ 6). */
function normaliseContact(value: string): string {
  return value.includes('@') ? value.toLowerCase() : normaliseMsisdn(value)
}

/** KBA (§6): billing postal code 3–12 and registered email or second mobile 3–254, both required. */
export const kbaSchema = z.object({
  postalCode: z
    .string()
    .trim()
    .min(3, 'Enter the billing postal code.')
    .max(12, 'Use at most 12 characters.'),
  registeredContact: z
    .string()
    .trim()
    .min(3, 'Enter the registered email or second mobile number.')
    .max(254, 'Use at most 254 characters.')
    .transform(normaliseContact),
})
export type KbaInput = z.input<typeof kbaSchema>
export type KbaValues = z.output<typeof kbaSchema>
export const KBA_FIELDS = ['postalCode', 'registeredContact'] as const

/** The identity-check problem `reason` of a 422 (backend R2–R3), checked at runtime. */
export function checkReason(error: unknown): InboundVerificationProblem['reason'] | undefined {
  if (!(error instanceof ApiError) || error.problem === null) return undefined
  const reason = (error.problem as InboundVerificationProblem).reason
  return typeof reason === 'string' ? reason : undefined
}

/** `attemptsRemaining` of a 422 identity-check problem, or `undefined` when absent. */
export function checkAttemptsRemaining(error: unknown): number | undefined {
  if (!(error instanceof ApiError) || error.problem === null) return undefined
  const value = (error.problem as InboundVerificationProblem).attemptsRemaining
  return typeof value === 'number' ? value : undefined
}


/** A root alert's text and the error whose correlation id it shows. */
export type RootProblem = { message: string; error: ApiError | null }

export const mmss = (seconds: number) => `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
export const secondsUntil = (deadline: number, now: number) => Math.max(0, Math.ceil((deadline - now) / 1000))

/** A root alert that takes focus whenever a new problem is shown (§6: focus the root `Alert`). */
export function useRootFocus() {
  const ref = useRef<HTMLDivElement>(null)
  const [request, setRequest] = useState(0)
  useEffect(() => {
    if (request > 0) ref.current?.focus()
  }, [request])
  return { ref, focus: () => setRequest((n) => n + 1) }
}
