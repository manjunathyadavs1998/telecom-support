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
  type LucideIcon,
} from 'lucide-react'
import type { PaymentStatus, PublicCaseStatus, Severity, Subscription } from '@/types/api'

/** Status display labels, icons and tones (frontend.md §3 Payment status, §4, erratum FE1 labels; XC-08), shared by `StatusBadge` and plain-text uses. */

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

export type StatusBadgeProps =
  | { kind: 'case'; value: PublicCaseStatus }
  | { kind: 'severity'; value: Severity }
  | { kind: 'payment'; value: PaymentStatus }
  | { kind: 'subscription'; value: Subscription['status'] }

export const LOOKS = { case: CASE, severity: SEVERITY, payment: PAYMENT, subscription: SUBSCRIPTION } as const

/** The display label for a status value (also for plain-text uses such as announcements). */
export function statusLabel({ kind, value }: StatusBadgeProps): string {
  return (LOOKS[kind] as Record<string, Look>)[value]?.label ?? value
}
