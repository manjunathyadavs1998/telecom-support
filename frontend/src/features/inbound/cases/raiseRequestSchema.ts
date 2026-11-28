import { z } from 'zod'
import type { AgentCaseCreateRequest, AgentCategory, Severity } from '@/types/api'

export const SUBJECT_MIN = 5
export const SUBJECT_MAX = 150
export const STATEMENT_MIN = 10
export const STATEMENT_MAX = 4000
export const NOTES_MAX = 4000
export const REASON_MIN = 5
export const REASON_MAX = 200

export const SEVERITIES: readonly Severity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']

export const PREFERRED_CONTACTS = [
  { value: 'PHONE', label: 'Phone' },
  { value: 'SMS', label: 'SMS' },
  { value: 'EMAIL', label: 'Email' },
] as const satisfies readonly { value: AgentCaseCreateRequest['preferredContact']; label: string }[]

/** Form fields, also the `errors[].field` names a 400 maps onto (§6). */
export const RAISE_REQUEST_FIELDS = [
  'categoryCode',
  'subcategoryCode',
  'subscriptionId',
  'subject',
  'customerStatement',
  'agentNotes',
  'preferredContact',
  'severity',
  'severityReason',
] as const
export type RaiseRequestField = (typeof RAISE_REQUEST_FIELDS)[number]

/** What the form holds: strings throughout (Select / RadioGroup values) plus the override toggle. */
export type RaiseRequestInput = {
  categoryCode: string
  subcategoryCode: string
  subscriptionId: string
  subject: string
  customerStatement: string
  agentNotes: string
  preferredContact: AgentCaseCreateRequest['preferredContact']
  override: boolean
  severity: '' | Severity
  severityReason: string
}

export function raiseRequestDefaults(primarySubscriptionId: number | undefined): RaiseRequestInput {
  return {
    categoryCode: '',
    subcategoryCode: '',
    subscriptionId: primarySubscriptionId === undefined ? '' : String(primarySubscriptionId),
    subject: '',
    customerStatement: '',
    agentNotes: '',
    preferredContact: 'PHONE',
    override: false,
    severity: '',
    severityReason: '',
  }
}

/** §3.8 / §14 #4: subcategory `defaultSeverity` ?? category's; `null` when both are unknown ("set automatically"). */
export function derivedSeverity(
  categories: readonly AgentCategory[],
  categoryCode: string,
  subcategoryCode: string,
): Severity | null {
  const category = categories.find((c) => c.code === categoryCode)
  if (!category) return null
  const subcategory = category.subcategories.find((s) => s.code === subcategoryCode)
  return subcategory?.defaultSeverity ?? category.defaultSeverity ?? null
}

type SchemaContext = {
  categories: readonly AgentCategory[]
  lineIds: readonly number[]
  emailVerified: boolean
}

/**
 * 03-inbound/frontend.md §6 Raise request: category · subcategory iff it has any · line (one of the live lines) · subject trim
 * 5–150 · statement trim 10–4000 · notes trim ≤ 4000 (`''` → null) · EMAIL only if verified · override → severity required and
 * ≠ derived (any value when derived is unknown) + reason trim 5–200; else both `null`. The output is the I12 body.
 */
export function raiseRequestSchema({ categories, lineIds, emailVerified }: SchemaContext) {
  return z
    .object({
      categoryCode: z.string().min(1, 'Choose a category.'),
      subcategoryCode: z.string(),
      subscriptionId: z.string().min(1, 'Choose the line.'),
      subject: z
        .string()
        .trim()
        .min(SUBJECT_MIN, `Enter at least ${SUBJECT_MIN} characters.`)
        .max(SUBJECT_MAX, `Use at most ${SUBJECT_MAX} characters.`),
      customerStatement: z
        .string()
        .trim()
        .min(STATEMENT_MIN, `Enter at least ${STATEMENT_MIN} characters.`)
        .max(STATEMENT_MAX, `Use at most ${STATEMENT_MAX} characters.`),
      agentNotes: z.string().trim().max(NOTES_MAX, `Use at most ${NOTES_MAX} characters.`),
      preferredContact: z.enum(['PHONE', 'SMS', 'EMAIL']),
      override: z.boolean(),
      severity: z.union([z.literal(''), z.enum(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'])]),
      severityReason: z.string().trim(),
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
      if (values.subscriptionId !== '' && !lineIds.includes(Number(values.subscriptionId))) {
        ctx.addIssue({ code: 'custom', path: ['subscriptionId'], message: 'Choose the line.' })
      }
      if (values.preferredContact === 'EMAIL' && !emailVerified) {
        ctx.addIssue({ code: 'custom', path: ['preferredContact'], message: "The customer's email isn't verified." })
      }
      if (values.override) {
        const derived = derivedSeverity(categories, values.categoryCode, values.subcategoryCode)
        if (values.severity === '') {
          ctx.addIssue({ code: 'custom', path: ['severity'], message: 'Choose the priority.' })
        } else if (derived !== null && values.severity === derived) {
          ctx.addIssue({ code: 'custom', path: ['severity'], message: 'Choose a different priority, or turn off the override.' })
        }
        if (values.severityReason.length < REASON_MIN || values.severityReason.length > REASON_MAX) {
          ctx.addIssue({
            code: 'custom',
            path: ['severityReason'],
            message: `Explain the override in ${REASON_MIN}–${REASON_MAX} characters.`,
          })
        }
      }
    })
    .transform((values): AgentCaseCreateRequest => {
      const hasSubcategories = (categories.find((c) => c.code === values.categoryCode)?.subcategories.length ?? 0) > 0
      return {
        categoryCode: values.categoryCode,
        subcategoryCode: hasSubcategories ? values.subcategoryCode : null,
        subscriptionId: Number(values.subscriptionId),
        subject: values.subject,
        customerStatement: values.customerStatement,
        agentNotes: values.agentNotes === '' ? null : values.agentNotes,
        preferredContact: values.preferredContact,
        severity: values.override && values.severity !== '' ? values.severity : null,
        severityReason: values.override ? values.severityReason : null,
      }
    })
}
