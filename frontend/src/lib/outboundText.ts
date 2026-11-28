import type { ClosureType, QueueSort } from '@/types/api'

/**
 * Plain-language text for the outbound workspace (04-outbound/frontend.md §3, §4; XC-08). An unknown value reads "Other" (or the
 * server's own detail for problems) rather than a raw code.
 */

const OTHER = 'Other'

/** `map[key]` only for the map's own keys (a server value like `constructor` never reaches the prototype). */
function own<T>(map: Readonly<Record<string, T>>, key: string | null | undefined): T | undefined {
  return key != null && Object.hasOwn(map, key) ? map[key] : undefined
}

const CLOSURE_TYPE: Record<ClosureType, string> = {
  CUSTOMER_CONFIRMED: 'Customer confirmed',
  WINDOW_ELAPSED: 'Verification window elapsed',
}

export function closureTypeText(value: string | null | undefined): string {
  return own(CLOSURE_TYPE, value) ?? OTHER
}

export function caseTypeText(value: string | null | undefined): string {
  return own({ REQUEST: 'Request', COMPLAINT: 'Complaint' }, value) ?? OTHER
}

export function channelText(value: string | null | undefined): string {
  return own({ CUSTOMER_PORTAL: 'Customer portal', INBOUND_AGENT: 'Inbound agent' }, value) ?? OTHER
}

export function preferredContactText(value: string | null | undefined): string {
  return own({ PHONE: 'Phone', SMS: 'SMS', EMAIL: 'Email' }, value) ?? OTHER
}

/** The queue sort options in display order; the first is the default (O2). */
export const QUEUE_SORTS: readonly { value: QueueSort; label: string }[] = [
  { value: 'createdAt,desc', label: 'Created newest' },
  { value: 'createdAt,asc', label: 'Created oldest' },
  { value: 'updatedAt,desc', label: 'Updated newest' },
  { value: 'updatedAt,asc', label: 'Updated oldest' },
  { value: 'slaDueAt,asc', label: 'SLA due soonest' },
  { value: 'slaDueAt,desc', label: 'SLA due latest' },
  { value: 'severity,desc', label: 'Severity highest' },
  { value: 'severity,asc', label: 'Severity lowest' },
]

export function queueSortText(value: string | null | undefined): string {
  return QUEUE_SORTS.find((sort) => sort.value === value)?.label ?? OTHER
}

/** §3.3 copy per `CaseProblem.reason`; `undefined` when unknown (the caller shows the server's `detail`). */
const CASE_PROBLEM: Record<string, string> = {
  already_assigned: 'Someone else claimed this case first. The case has been refreshed.',
  stale_version: 'This case changed while you were working. Check the latest details and submit again.',
  case_closed: 'This case is resolved or closed.',
  unassigned: "Only the assigned agent or a supervisor can change this case. Claim it first if it's unassigned.",
  invalid_transition: 'This status change is no longer allowed. The case has been refreshed.',
  invalid_assignee: "This agent can't take cases now.",
  no_change: 'Already assigned to this agent.',
  closure_condition_unmet:
    "The verification window hasn't ended yet. Choose Customer confirmed, or wait for automatic closure.",
  reopen_window_expired: "Closed more than 7 days ago; it can't be reopened.",
  bookmark_limit: 'You can bookmark up to 200 cases. Remove some on the Bookmarks page.',
}

export function caseProblemText(reason: string | null | undefined): string | undefined {
  return own(CASE_PROBLEM, reason)
}
