import { z } from 'zod'
import { ApiError } from '@/services/api/errors'
import type { ContactChannel, VerificationProblem } from '@/types/api'

/** Copy per contact channel (frontend.md §3 Profile). */
export const CONTACT_COPY: Record<
  ContactChannel,
  { label: string; inputLabel: string; destination: string; verifiedToast: string }
> = {
  EMAIL: {
    label: 'email',
    inputLabel: 'New email address',
    destination: 'email address',
    verifiedToast: 'Email verified',
  },
  SECONDARY_MSISDN: {
    label: 'secondary mobile',
    inputLabel: 'New mobile number',
    destination: 'mobile number',
    verifiedToast: 'Mobile number verified',
  },
}

export const EMAIL_MAX = 254
const INDIAN_MOBILE = /^[6-9]\d{9}$/
const E164 = /^\+[1-9]\d{7,14}$/

/** OQ 4: a 10-digit Indian mobile (first digit 6–9) becomes `+91…`; spaces and hyphens are ignored. */
export function normaliseMsisdn(input: string): string {
  const compact = input.replace(/[\s-]/g, '')
  return INDIAN_MOBILE.test(compact) ? `+91${compact}` : compact
}

const emailValue = z
  .string()
  .trim()
  .toLowerCase()
  .min(1, 'Enter an email address.')
  .max(EMAIL_MAX, `Use at most ${EMAIL_MAX} characters.`)
  .pipe(z.email('Enter a valid email address.'))

function msisdnValue(primaryMsisdn: string) {
  return z
    .string()
    .trim()
    .min(1, 'Enter a mobile number.')
    .transform(normaliseMsisdn)
    .refine((value) => E164.test(value), 'Enter a 10-digit mobile number, or the full number with its country code (+…).')
    .refine((value) => value !== primaryMsisdn, 'This is already your primary mobile number.')
}

/**
 * Contact start form (frontend.md §6): EMAIL → trimmed, lower-cased, ≤ 254, valid address; SECONDARY_MSISDN → a 10-digit
 * number normalised to `+91…`, else E.164, and not the primary number.
 */
export function contactStartSchema(channel: ContactChannel, primaryMsisdn: string) {
  return z.object({ value: channel === 'EMAIL' ? emailValue : msisdnValue(primaryMsisdn) })
}

export type ContactStartInput = { value: string }

/** Contact confirm form (frontend.md §6): exactly six digits. */
export const contactCodeSchema = z.object({
  code: z.string().trim().regex(/^\d{6}$/, 'Enter the 6-digit code.'),
})

export type ContactCodeInput = z.input<typeof contactCodeSchema>

/**
 * `attemptsRemaining` of an E4 422 (`VerificationProblem`, frontend.md §4, review F13), checked at runtime; `undefined` for
 * any other error or when the field is missing (expired or locked codes, OQ 5).
 */
export function attemptsRemaining(error: unknown): number | undefined {
  if (!(error instanceof ApiError) || error.status !== 422 || error.problem === null) return undefined
  const value = (error.problem as VerificationProblem).attemptsRemaining
  return typeof value === 'number' ? value : undefined
}
