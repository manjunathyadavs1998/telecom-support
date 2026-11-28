import { useId, type ReactNode } from 'react'
import { StatusBadge } from '@/components/data/StatusBadge'
import { LoadingState } from '@/components/states/LoadingState'
import { SectionErrorState } from '@/components/states/SectionErrorState'
import { Badge } from '@/components/ui/badge'
import { formatDateTime } from '@/lib/format'
import { suspensionReasonText } from '@/lib/inboundText'
import { cn } from '@/lib/utils'
import { useAgentProfileQuery, useVerifiedCustomer } from './queries'

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="text-foreground">{children}</dd>
    </div>
  )
}

/** "Verified" / "Not verified" as text (never colour only, XC-08). */
function ContactState({ value, verified }: { value: string | null | undefined; verified: boolean }) {
  if (!value) return <span className="text-muted-foreground">Not set</span>
  return (
    <span className="flex flex-wrap items-center gap-2">
      <span className="break-all">{value}</span>
      <Badge
        className={cn(
          verified
            ? 'border-success/30 bg-success/10 text-success-foreground'
            : 'border-warning/30 bg-warning/10 text-warning-foreground',
        )}
      >
        {verified ? 'Verified' : 'Not verified'}
      </Badge>
    </span>
  )
}

/** Overview Profile (03-inbound/frontend.md §3.4, I5; INB-01.3). No billing address (backend R7). */
export function ProfileSection() {
  const headingId = useId()
  const { customerId, verified } = useVerifiedCustomer()
  const query = useAgentProfileQuery(customerId, verified)
  const profile = query.data

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 enter-soft rounded-xl border border-line bg-surface shadow-soft-sm p-4 md:p-6">
      <h2 id={headingId} className="text-lg font-semibold text-foreground">
        Profile
      </h2>
      {query.isError ? (
        <SectionErrorState title="We couldn't load the profile" error={query.error} onRetry={() => void query.refetch()} />
      ) : !profile ? (
        <LoadingState label="Loading profile" />
      ) : (
        <dl className="grid gap-4 sm:grid-cols-2">
          <Field label="Full name">{profile.fullName}</Field>
          <Field label="Alias">{profile.aliasName || <span className="text-muted-foreground">Not set</span>}</Field>
          <Field label="Customer ref">{profile.customerRef}</Field>
          <Field label="Account ref">{profile.accountRef}</Field>
          <Field label="Customer status">
            <StatusBadge kind="customer" value={profile.customerStatus} />
          </Field>
          <Field label="Account status">
            <span className="flex flex-col gap-1">
              <StatusBadge kind="account" value={profile.accountStatus} />
              {profile.suspension ? (
                <span className="text-sm text-muted-foreground">
                  {suspensionReasonText(profile.suspension.reason)} since {formatDateTime(profile.suspension.since)}
                </span>
              ) : null}
            </span>
          </Field>
          <Field label="Primary mobile">{profile.primaryMsisdn ?? <span className="text-muted-foreground">Not set</span>}</Field>
          <Field label="Email">
            <ContactState value={profile.email} verified={profile.emailVerified} />
          </Field>
          <Field label="Second mobile">
            <ContactState value={profile.secondaryMsisdn} verified={profile.secondaryMsisdnVerified} />
          </Field>
        </dl>
      )}
    </section>
  )
}
