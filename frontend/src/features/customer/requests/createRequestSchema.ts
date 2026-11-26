import { z } from 'zod'
import type { CaseCreateRequest, Category } from '@/types/api'

export const SUBJECT_MIN = 5
export const SUBJECT_MAX = 150
export const DESCRIPTION_MIN = 10
export const DESCRIPTION_MAX = 4000
/** The description counter is announced (politely) only from this many characters (frontend.md §3 Create request, F18). */
export const DESCRIPTION_ANNOUNCE_FROM = 3900

/** The number picker's "Not specific" option (OQ 7): no `subscriptionId` is linked. */
export const NOT_SPECIFIC = 'none'

export const VERIFY_EMAIL_HINT = 'Verify your email in Profile first'

export const PREFERRED_CONTACTS = [
  { value: 'PHONE', label: 'Phone' },
  { value: 'SMS', label: 'SMS' },
  { value: 'EMAIL', label: 'Email' },
] as const satisfies readonly { value: CaseCreateRequest['preferredContact']; label: string }[]

/** The form's field names, also the `errors[].field` names a 400 maps onto (frontend.md §6). */
export const CREATE_REQUEST_FIELDS = [
  'categoryCode',
  'subcategoryCode',
  'subscriptionId',
  'subject',
  'description',
  'preferredContact',
] as const

export type CreateRequestField = (typeof CREATE_REQUEST_FIELDS)[number]

/** What the form holds: strings throughout (Select/RadioGroup values). */
export type CreateRequestInput = {
  categoryCode: string
  subcategoryCode: string
  subscriptionId: string
  subject: string
  description: string
  preferredContact: CaseCreateRequest['preferredContact']
}

export const CREATE_REQUEST_DEFAULTS: CreateRequestInput = {
  categoryCode: '',
  subcategoryCode: '',
  subscriptionId: NOT_SPECIFIC,
  subject: '',
  description: '',
  preferredContact: 'PHONE',
}

/**
 * frontend.md §6 Create request: `categoryCode` required · `subcategoryCode` required iff the category has subcategories ·
 * `subscriptionId` optional ("Not specific" → null) · `subject` trimmed 5–150 · `description` trimmed 10–4000 ·
 * `preferredContact` EMAIL only with a verified email. The output is the E12 body; severity is never sent (§14 #4).
 */
export function createRequestSchema({ categories, emailVerified }: { categories: readonly Category[]; emailVerified: boolean }) {
  return z
    .object({
      categoryCode: z.string().min(1, 'Choose a category.'),
      subcategoryCode: z.string(),
      subscriptionId: z.string(),
      subject: z
        .string()
        .trim()
        .min(SUBJECT_MIN, `Enter at least ${SUBJECT_MIN} characters.`)
        .max(SUBJECT_MAX, `Use at most ${SUBJECT_MAX} characters.`),
      description: z
        .string()
        .trim()
        .min(DESCRIPTION_MIN, `Enter at least ${DESCRIPTION_MIN} characters.`)
        .max(DESCRIPTION_MAX, `Use at most ${DESCRIPTION_MAX} characters.`),
      preferredContact: z.enum(['PHONE', 'SMS', 'EMAIL']),
    })
    .superRefine((values, ctx) => {
      const category = categories.find((c) => c.code === values.categoryCode)
      if (values.categoryCode !== '' && !category) {
        ctx.addIssue({ code: 'custom', path: ['categoryCode'], message: 'Choose a category.' })
      }
      const subcategories = category?.subcategories ?? []
      if (subcategories.length > 0 && !subcategories.some((s) => s.code === values.subcategoryCode)) {
        ctx.addIssue({ code: 'custom', path: ['subcategoryCode'], message: 'Choose a subcategory.' })
      }
      if (values.preferredContact === 'EMAIL' && !emailVerified) {
        ctx.addIssue({ code: 'custom', path: ['preferredContact'], message: `${VERIFY_EMAIL_HINT}.` })
      }
    })
    .transform(
      (values): CaseCreateRequest => {
        const hasSubcategories = (categories.find((c) => c.code === values.categoryCode)?.subcategories.length ?? 0) > 0
        return {
          categoryCode: values.categoryCode,
          subcategoryCode: hasSubcategories ? values.subcategoryCode : null,
          subscriptionId: values.subscriptionId === NOT_SPECIFIC ? null : Number(values.subscriptionId),
          subject: values.subject,
          description: values.description,
          preferredContact: values.preferredContact,
        }
      },
    )
}
