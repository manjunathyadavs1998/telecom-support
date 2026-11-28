import type { EligibilityReason, ReactivationEligibility, SuspensionReason } from '@/types/api'

/**
 * Plain-language text for the inbound workspace (03-inbound/frontend.md §4, §3.3, §3.9; INB-04.1, XC-08). An unknown value
 * (a contract addition the UI does not know yet) reads "Other" rather than a raw code.
 */

const OTHER = 'Other'

/** `map[key]` only for the map's own keys, so a server value like `constructor` never reaches the prototype. */
function own<T>(map: Readonly<Record<string, T>>, key: string | null | undefined): T | undefined {
  return key != null && Object.hasOwn(map, key) ? map[key] : undefined
}

const SUSPENSION_REASON: Record<SuspensionReason, string> = {
  NON_PAYMENT: 'Non-payment',
  CUSTOMER_REQUEST: 'Customer request',
  KYC_PENDING: 'KYC pending',
  FRAUD: 'Fraud',
  LEGAL: 'Legal',
  REGULATORY: 'Regulatory',
  OTHER: 'Other',
}

/** The suspension reason label (header, eligibility, history). */
export function suspensionReasonText(reason: string | null | undefined): string {
  return own(SUSPENSION_REASON, reason) ?? OTHER
}

/** Context some eligibility sentences need (§3.9): the suspension reason and the open request's reference. */
export type EligibilityContext = Pick<ReactivationEligibility, 'suspensionReason' | 'openRequestReference' | 'maxHours'>

const ELIGIBILITY: Record<EligibilityReason, (context: EligibilityContext) => string> = {
  CUSTOMER_INACTIVE: () => 'The customer is inactive, so service cannot be restored.',
  NOT_SUSPENDED: () => 'The account is not suspended, so there is nothing to restore.',
  PROHIBITED_REASON: ({ suspensionReason }) =>
    `Suspended for ${suspensionReasonText(suspensionReason)}. This can never be overridden.`,
  REASON_NOT_ELIGIBLE: ({ suspensionReason }) =>
    `Accounts suspended for ${suspensionReasonText(suspensionReason)} can't be restored temporarily.`,
  NO_SUSPENDED_SERVICE: () => 'No suspended number on this account can be restored.',
  OPEN_REQUEST: ({ openRequestReference }) =>
    openRequestReference
      ? `A restoration is already open: ${openRequestReference}.`
      : 'A restoration is already open for this account.',
  ACCOUNT_LIMIT_REACHED: () => 'This account has reached its limit of temporary restorations.',
  AGENT_LIMIT_REACHED: () => 'You have reached your limit of restorations for now. Try again later or ask another approver.',
  HOURS_EXCEED_POLICY: ({ maxHours }) =>
    maxHours ? `At most ${maxHours} hours can be requested.` : 'The requested hours exceed the policy.',
}

/** One sentence per `EligibilityReason` (§3.9 not-eligible list and I14 422 root list). */
export function eligibilityText(reason: string, context: EligibilityContext = {}): string {
  return own(ELIGIBILITY, reason)?.(context) ?? OTHER
}

const END_REASON: Record<string, string> = {
  expired: 'Time limit reached',
  account_reinstated: 'Account reinstated',
  policy_prohibited: 'Ended early by policy',
}

/** Why an EXPIRED / ENDED restoration ended (§3.9 History). */
export function endReasonText(reason: string | null | undefined): string {
  return own(END_REASON, reason) ?? OTHER
}

const FAILURE_REASON: Record<string, string> = {
  provider_rejected: 'Rejected by provider',
  provider_unavailable: 'Provider unavailable',
  not_created: 'Not restored',
  not_restored: 'Not restored',
}

/** Why a FAILED restoration failed (§3.9 History). */
export function failureReasonText(reason: string | null | undefined): string {
  return own(FAILURE_REASON, reason) ?? OTHER
}
