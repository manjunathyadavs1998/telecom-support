import { z } from 'zod'
import { normaliseMsisdn } from '@/features/customer/profile/contactSchemas'

const E164 = /^\+[1-9]\d{7,14}$/

/**
 * Customer search form (03-inbound/frontend.md §6, OQ 6): trimmed, spaces/dashes stripped; a 10-digit number starting 6–9
 * becomes `+91…`, else E.164.
 */
export const searchSchema = z.object({
  msisdn: z
    .string()
    .trim()
    .min(1, 'Enter a mobile number.')
    .transform(normaliseMsisdn)
    .refine((value) => E164.test(value), 'Enter a 10-digit mobile number, or the full number with its country code (+…).'),
})

export type SearchInput = z.input<typeof searchSchema>
export type SearchValues = z.output<typeof searchSchema>
export const SEARCH_FIELDS = ['msisdn'] as const
