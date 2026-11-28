import {
  TriangleAlert,
  Ban,
  CircleCheck,
  CircleDot,
  Clock,
  Hourglass,
  MessageSquare,
  CirclePause,
  Search,
  SignalHigh,
  SignalLow,
  SignalMedium,
  CircleX,
  RotateCcw,
  UserCheck,
  CircleAlert,
  type LucideIcon,
} from 'lucide-react'
import type {
  AccountStatus,
  CaseStatus,
  CustomerStatus,
  PaymentStatus,
  PublicCaseStatus,
  ReactivationStatus,
  Severity,
  Subscription,
} from '@/types/api'

/**
 * Status display labels, icons and tones (frontend.md §3 Payment status, §4, erratum FE1 labels; 03-inbound/frontend.md §4,
 * §3.9; XC-08), shared by `StatusBadge` and plain-text uses.
 */

export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'
export type Look = { label: string; icon: LucideIcon; tone: Tone }


const CASE: Record<PublicCaseStatus, Look> = {
  SUBMITTED: { label: 'Open', icon: CircleDot, tone: 'info' },
  IN_PROGRESS: { label: 'In progress', icon: Clock, tone: 'info' },
  AWAITING_YOUR_REPLY: { label: 'Awaiting your reply', icon: MessageSquare, tone: 'warning' },
  RESOLVED: { label: 'Resolved', icon: CircleCheck, tone: 'success' },
  CLOSED: { label: 'Closed', icon: CircleCheck, tone: 'neutral' },
}

const SEVERITY: Record<Severity, Look> = {
  LOW: { label: 'Low', icon: SignalLow, tone: 'neutral' },
  MEDIUM: { label: 'Medium', icon: SignalMedium, tone: 'info' },
  HIGH: { label: 'High', icon: SignalHigh, tone: 'warning' },
  CRITICAL: { label: 'Critical', icon: TriangleAlert, tone: 'danger' },
}

// frontend.md §3 Payment status: "PENDING Processing, SUCCEEDED Paid, FAILED Failed, EXPIRED Expired, REVIEW Under review".
const PAYMENT: Record<PaymentStatus, Look> = {
  PENDING: { label: 'Processing', icon: Hourglass, tone: 'info' },
  SUCCEEDED: { label: 'Paid', icon: CircleCheck, tone: 'success' },
  FAILED: { label: 'Failed', icon: CircleX, tone: 'danger' },
  EXPIRED: { label: 'Expired', icon: Clock, tone: 'neutral' },
  REVIEW: { label: 'Under review', icon: Search, tone: 'warning' },
}

const SUBSCRIPTION: Record<Subscription['status'], Look> = {
  ACTIVE: { label: 'Active', icon: CircleCheck, tone: 'success' },
  SUSPENDED: { label: 'Suspended', icon: CirclePause, tone: 'warning' },
  TERMINATED: { label: 'Terminated', icon: Ban, tone: 'neutral' },
}

// 03-inbound/frontend.md §4: the internal case lifecycle the agent sees (the public label is secondary text).
const CASE_INTERNAL: Record<CaseStatus, Look> = {
  CREATED: { label: 'Created', icon: CircleDot, tone: 'info' },
  ASSIGNED: { label: 'Assigned', icon: UserCheck, tone: 'info' },
  IN_PROGRESS: { label: 'In progress', icon: Clock, tone: 'info' },
  PENDING_CUSTOMER: { label: 'Pending customer', icon: MessageSquare, tone: 'warning' },
  PENDING_EXTERNAL: { label: 'Pending external', icon: Hourglass, tone: 'warning' },
  RESOLVED: { label: 'Resolved', icon: CircleCheck, tone: 'success' },
  CLOSED: { label: 'Closed', icon: CircleCheck, tone: 'neutral' },
  REOPENED: { label: 'Reopened', icon: RotateCcw, tone: 'warning' },
}

const CUSTOMER: Record<CustomerStatus, Look> = {
  ACTIVE: { label: 'Active', icon: CircleCheck, tone: 'success' },
  INACTIVE: { label: 'Inactive', icon: Ban, tone: 'neutral' },
}

const ACCOUNT: Record<AccountStatus, Look> = {
  ACTIVE: { label: 'Active', icon: CircleCheck, tone: 'success' },
  SUSPENDED: { label: 'Suspended', icon: CirclePause, tone: 'warning' },
  CLOSED: { label: 'Closed', icon: Ban, tone: 'neutral' },
}

// 03-inbound/frontend.md §3.9 History: honest status copy (INB-04.4/04.5, BR-08).
const REACTIVATION: Record<ReactivationStatus, Look> = {
  PENDING: { label: 'Waiting for provider', icon: Hourglass, tone: 'info' },
  ACTIVE: { label: 'Restored', icon: CircleCheck, tone: 'success' },
  EXPIRING: { label: 'Ending', icon: Clock, tone: 'warning' },
  EXPIRED: { label: 'Expired', icon: CirclePause, tone: 'neutral' },
  ENDED: { label: 'Ended', icon: CirclePause, tone: 'neutral' },
  FAILED: { label: 'Failed', icon: CircleAlert, tone: 'danger' },
}

export type StatusBadgeProps =
  | { kind: 'case'; value: PublicCaseStatus }
  | { kind: 'severity'; value: Severity }
  | { kind: 'payment'; value: PaymentStatus }
  | { kind: 'subscription'; value: Subscription['status'] }
  | { kind: 'caseInternal'; value: CaseStatus }
  | { kind: 'customer'; value: CustomerStatus }
  | { kind: 'account'; value: AccountStatus }
  | { kind: 'reactivation'; value: ReactivationStatus }

export const LOOKS = {
  case: CASE,
  severity: SEVERITY,
  payment: PAYMENT,
  subscription: SUBSCRIPTION,
  caseInternal: CASE_INTERNAL,
  customer: CUSTOMER,
  account: ACCOUNT,
  reactivation: REACTIVATION,
} as const

/** The display label for a status value (also for plain-text uses such as announcements). */
export function statusLabel({ kind, value }: StatusBadgeProps): string {
  return (LOOKS[kind] as Record<string, Look>)[value]?.label ?? value
}
