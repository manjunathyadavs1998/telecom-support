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
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import type { PaymentStatus, PublicCaseStatus, Severity, Subscription } from '@/types/api'

type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'
type Look = { label: string; icon: LucideIcon; tone: Tone }

const TONES: Record<Tone, string> = {
  neutral: 'border-slate-300 bg-slate-100 text-foreground',
  info: 'border-primary/30 bg-primary/10 text-primary',
  success: 'border-success/30 bg-success/10 text-success',
  warning: 'border-warning/30 bg-warning/10 text-warning',
  danger: 'border-destructive/30 bg-destructive/10 text-destructive',
}

const CASE: Record<PublicCaseStatus, Look> = {
  SUBMITTED: { label: 'Submitted', icon: CircleDot, tone: 'info' },
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

const LOOKS = { case: CASE, severity: SEVERITY, payment: PAYMENT, subscription: SUBSCRIPTION } as const

/** The display label for a status value (also for plain-text uses such as announcements). */
export function statusLabel({ kind, value }: StatusBadgeProps): string {
  return (LOOKS[kind] as Record<string, Look>)[value]?.label ?? value
}

/**
 * Status badge (frontend.md §4, XC-08): text label + icon + token colour, so the meaning never depends on colour alone. An unknown
 * value (a newer server) falls back to the raw value in the neutral style.
 */
export function StatusBadge(props: StatusBadgeProps & { className?: string }) {
  const look: Look = (LOOKS[props.kind] as Record<string, Look>)[props.value] ?? {
    label: props.value,
    icon: CircleDot,
    tone: 'neutral',
  }
  const Icon = look.icon
  return (
    <Badge className={cn('gap-1', TONES[look.tone], props.className)} data-status={props.value}>
      <Icon aria-hidden="true" className="size-3.5" />
      {look.label}
    </Badge>
  )
}
