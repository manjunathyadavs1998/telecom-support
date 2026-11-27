import { Info } from 'lucide-react'
import { useId, type ReactNode } from 'react'
import { PageContainer } from '@/components/layout/PageContainer'
import { ErrorState } from '@/components/states/ErrorState'
import { LoadingState } from '@/components/states/LoadingState'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { ForbiddenPage } from '@/pages/ForbiddenPage'
import { Can } from '@/permissions/Can'
import { ApiError } from '@/services/api/errors'
import type { BillingAddress, ContactChannel, Profile } from '@/types/api'
import { AliasForm } from './AliasForm'
import { ContactChangeDialog } from './ContactChangeDialog'
import { useProfileQuery } from './queries'

export const BILLING_ADDRESS_NOTICE = "You can't change your billing address here. Contact customer support to change it."

function Section({ title, children }: { title: string; children: ReactNode }) {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 rounded-lg border bg-white p-4 md:p-6">
      <h2 id={headingId} className="text-lg font-semibold text-foreground">
        {title}
      </h2>
      {children}
    </section>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="text-foreground">{children}</dd>
    </div>
  )
}

/** "Verified" / "Not verified" as text with a token colour (never colour only, XC-08). */
function VerifiedBadge({ verified }: { verified: boolean }) {
  return (
    <Badge
      className={cn(
        verified
          ? 'border-success/30 bg-success/10 text-success-foreground'
          : 'border-warning/30 bg-warning/10 text-warning-foreground',
      )}
    >
      {verified ? 'Verified' : 'Not verified'}
    </Badge>
  )
}

function ContactValue({ value, verified }: { value: string | null | undefined; verified: boolean }) {
  if (!value) return <span className="text-muted-foreground">Not set</span>
  return (
    <span className="flex flex-wrap items-center gap-2">
      <span className="break-all">{value}</span>
      <VerifiedBadge verified={verified} />
    </span>
  )
}

/** A contact row: value + verification state, and "Change" / "Add" with `customer.profile.update` (CUS-02.2). */
function ContactRow({
  label,
  channel,
  value,
  verified,
  primaryMsisdn,
}: {
  label: string
  channel: ContactChannel
  value: string | null | undefined
  verified: boolean
  primaryMsisdn: string
}) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="flex flex-wrap items-center justify-between gap-2 text-foreground">
        <ContactValue value={value} verified={verified} />
        <Can allOf={['customer.profile.update']}>
          <ContactChangeDialog channel={channel} hasValue={Boolean(value)} primaryMsisdn={primaryMsisdn} />
        </Can>
      </dd>
    </div>
  )
}

function AddressLines({ address }: { address: BillingAddress }) {
  const lines = [
    address.line1,
    address.line2,
    `${address.city}, ${address.state} ${address.postalCode}`,
    address.country,
  ].filter((line): line is string => Boolean(line))
  return (
    <address className="not-italic">
      {lines.map((line) => (
        <span key={line} className="block">
          {line}
        </span>
      ))}
    </address>
  )
}

function ProfileDetails({ profile }: { profile: Profile }) {
  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Section title="Personal details">
        <dl className="grid gap-4 sm:grid-cols-2">
          <Field label="Full name">{profile.fullName}</Field>
          <Field label="Primary mobile">{profile.primaryMsisdn}</Field>
          <Field label="Customer reference">{profile.customerRef}</Field>
          <Field label="Account reference">{profile.accountRef}</Field>
        </dl>
        <Can
          allOf={['customer.profile.update']}
          fallback={
            <dl>
              <Field label="Alias">{profile.aliasName ?? <span className="text-muted-foreground">Not set</span>}</Field>
            </dl>
          }
        >
          <AliasForm profile={profile} />
        </Can>
      </Section>
      <Section title="Contact details">
        <dl className="flex flex-col gap-4">
          <ContactRow
            label="Email"
            channel="EMAIL"
            value={profile.email}
            verified={profile.emailVerified}
            primaryMsisdn={profile.primaryMsisdn}
          />
          <ContactRow
            label="Secondary mobile"
            channel="SECONDARY_MSISDN"
            value={profile.secondaryMsisdn}
            verified={profile.secondaryMsisdnVerified}
            primaryMsisdn={profile.primaryMsisdn}
          />
        </dl>
      </Section>
      <Section title="Billing address">
        <AddressLines address={profile.billingAddress} />
        <Alert asChild>
          <p>
            <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-primary" />
            <span>{BILLING_ADDRESS_NOTICE}</span>
          </p>
        </Alert>
      </Section>
    </div>
  )
}

/**
 * Profile `/customer/profile` (frontend.md §3 Profile; CUS-02.1/02.3/02.4): read-only identity, alias edit (with
 * `customer.profile.update`), contacts with their verification state, and the read-only billing address. A 403 on the profile
 * query (the page's primary query) → `ForbiddenPage`; any other error → `ErrorState` with retry.
 */
export function ProfilePage() {
  const query = useProfileQuery()
  if (query.isError && query.error instanceof ApiError && query.error.status === 403) return <ForbiddenPage />
  return (
    <PageContainer title="Profile" description="Your personal details, contacts and billing address.">
      {query.isPending ? (
        <LoadingState label="Loading profile" />
      ) : query.isError ? (
        <ErrorState title="We couldn't load your profile" error={query.error} onRetry={() => void query.refetch()} />
      ) : (
        <ProfileDetails profile={query.data} />
      )}
    </PageContainer>
  )
}

/** `route.lazy` entry (pageRegistry). */
export { ProfilePage as Component }
