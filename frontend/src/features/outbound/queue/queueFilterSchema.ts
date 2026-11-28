import { z } from 'zod'
import { normaliseMsisdn } from '@/features/customer/profile/contactSchemas'
import { requestReferenceSchema } from '@/features/customer/requests/queries'

const E164 = /^\+[1-9]\d{7,14}$/

/**
 * The queue filter panel's free-text fields (04-outbound/frontend.md §6): customer mobile (10 digits → `+91…`, else E.164) and
 * case ID (`SR-…`), each optional. Dates use `DateRangeFilter`; checkboxes and selects need no schema.
 */
export const queueFilterSchema = z.object({
  msisdn: z.union([
    z.literal(''),
    z
      .string()
      .trim()
      .transform(normaliseMsisdn)
      .refine((value) => value === '' || E164.test(value), 'Enter a 10-digit mobile number, or the full number with +.'),
  ]),
  ref: z.union([z.literal(''), requestReferenceSchema]),
})

export type QueueFilterInput = z.input<typeof queueFilterSchema>
export type QueueFilterValues = z.output<typeof queueFilterSchema>
