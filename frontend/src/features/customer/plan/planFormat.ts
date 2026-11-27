import { formatDate, formatInr } from '@/lib/format'
import type { Subscription } from '@/types/api'

/** "Changing to {toPlanCode} on {date}" (frontend.md §3 My plan), or null without a pending change. */
export function pendingChangeText(subscription: Subscription): string | null {
  const change = subscription.pendingPlanChange
  return change ? `Changing to ${change.toPlanCode} on ${formatDate(change.effectiveDate)}` : null
}

/** `₹999.00 / month` (display only, F8). */
export const perMonth = (amount: string) => `${formatInr(amount)} / month`
