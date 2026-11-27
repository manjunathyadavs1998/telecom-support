import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { Inbox, Receipt, Smartphone, Wallet } from 'lucide-react'
import { useId, type ReactNode } from 'react'
import { Link } from 'react-router'
import { OWN_FORBIDDEN_HANDLING } from '@/app/queryClient'
import { StatusBadge } from '@/components/data/StatusBadge'
import { EmptyState } from '@/components/states/EmptyState'
import { LoadingState } from '@/components/states/LoadingState'
import { SECTION_FORBIDDEN_TITLE, SectionErrorState } from '@/components/states/SectionErrorState'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { formatDate, formatDateTime, formatInr, isPositiveMoney } from '@/lib/format'
import { PAY_BILL_PATH, PAYMENT_HISTORY_PATH, paymentStatusPath } from '@/features/customer/payments/paymentFormat'
import { fetchOpenInvoices, fetchPayments, openInvoicesQueryKey, paymentsQueryKey } from '@/features/customer/payments/queries'
import { perMonth } from '@/features/customer/plan/planFormat'
import { primaryFirst, subscriptionsQuery } from '@/features/customer/plan/queries'
import { profileQuery } from '@/features/customer/profile/queries'
import { promotionsQuery } from '@/features/customer/promotions/queries'
import { casesQueryKey, fetchCases } from '@/features/customer/requests/queries'
import { Can } from '@/permissions/Can'
import type { Permission } from '@/types/api'

/** frontend.md §3 403 rule (F2): a 403 on a dashboard card (a secondary query) shows in that card only. */
export const CARD_FORBIDDEN_TITLE = SECTION_FORBIDDEN_TITLE

/** Dashboard card size (OQ 11): 3 rows on request and bill cards; one last payment. */
export const DASHBOARD_CARD_SIZE = 3

export const PLAN_PATH = '/customer/plan'
export const REQUESTS_PATH = '/customer/requests'
export const CREATE_REQUEST_PATH = '/customer/requests/new'
export const PROMOTIONS_PATH = '/customer/promotions'

const RECENT_CASES_QUERY = { page: 0, size: DASHBOARD_CARD_SIZE }
const LAST_PAYMENT_QUERY = { page: 0, size: 1 }
const LIST_STALE_TIME_MS = 30_000

const linkClass = 'font-medium text-primary underline-offset-4 hover:underline'

type CardProps = {
  title: string
  /** e.g. "View all" link in the card header. */
  action?: ReactNode
  className?: string
  children: ReactNode
}

/** One dashboard card: a `<section aria-labelledby>` with its own heading (frontend.md §3 Dashboard). */
function DashboardCard({ title, action, className, children }: CardProps) {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} className={cn('flex flex-col gap-4 rounded-lg border bg-white p-4 md:p-6', className)}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={headingId} className="text-lg font-semibold text-foreground">
          {title}
        </h2>
        {action}
      </div>
      {children}
    </section>
  )
}

type QueryBodyProps<T> = {
  query: UseQueryResult<T>
  loadingLabel: string
  errorTitle: string
  children: (data: T) => ReactNode
}

/** A card's own loading / error (403 → "isn't available") / data states (XC-03, CUS-01.2). */
function QueryBody<T>({ query, loadingLabel, errorTitle, children }: QueryBodyProps<T>) {
  if (query.isPending) return <LoadingState label={loadingLabel} />
  if (query.isError) return <SectionErrorState title={errorTitle} error={query.error} onRetry={() => void query.refetch()} />
  return <>{children(query.data)}</>
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="break-words text-foreground">{children}</dd>
    </div>
  )
}

/** A card shown only with its read permission, so a user without it never fires its query (CUS-01, BR-10). */
function Gated({ allOf, children }: { allOf: readonly Permission[]; children: ReactNode }) {
  return <Can allOf={allOf}>{children}</Can>
}

/** Identity (E1): name · primary mobile · account ref · customer ref (CUS-01.1). */
function IdentityCardBody() {
  const query = useQuery({ ...profileQuery, meta: OWN_FORBIDDEN_HANDLING })
  return (
    <DashboardCard title="My account" className="md:col-span-2">
      <QueryBody query={query} loadingLabel="Loading your account" errorTitle="We couldn't load your account">
        {(profile) => (
          <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Field label="Name">{profile.fullName}</Field>
            <Field label="Primary mobile">{profile.primaryMsisdn}</Field>
            <Field label="Account ref">{profile.accountRef}</Field>
            <Field label="Customer ref">{profile.customerRef}</Field>
          </dl>
        )}
      </QueryBody>
    </DashboardCard>
  )
}

export function IdentityCard() {
  return (
    <Gated allOf={['customer.profile.read']}>
      <IdentityCardBody />
    </Gated>
  )
}

/** Promotion banner (E8): the first offer with a claimable number; hidden when there is none (CUS-01.1). */
function PromotionBannerBody() {
  const query = useQuery({ ...promotionsQuery, meta: OWN_FORBIDDEN_HANDLING })
  const offer = query.data?.find((promotion) => promotion.eligibleSubscriptionIds.length > 0)
  if (query.isSuccess && !offer) return null
  return (
    <DashboardCard title="Offer for you" className="md:col-span-2">
      <QueryBody query={query} loadingLabel="Loading offers" errorTitle="We couldn't load your offers">
        {() =>
          offer ? (
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex flex-col gap-1">
                <p className="font-semibold text-foreground">{offer.title}</p>
                <p className="text-foreground">{offer.shortDescription}</p>
              </div>
              <Button asChild variant="outline">
                <Link to={`${PROMOTIONS_PATH}#${encodeURIComponent(offer.code)}`}>
                  View offer<span className="sr-only">: {offer.title}</span>
                </Link>
              </Button>
            </div>
          ) : null
        }
      </QueryBody>
    </DashboardCard>
  )
}

export function PromotionBanner() {
  return (
    <Gated allOf={['promotion.read']}>
      <PromotionBannerBody />
    </Gated>
  )
}

/** My current plan (E5): the primary subscription's plan, ₹/month and status (CUS-01.1); links to My plan (CUS-01.4). */
function PlanCardBody() {
  const query = useQuery({ ...subscriptionsQuery, meta: OWN_FORBIDDEN_HANDLING })
  return (
    <DashboardCard
      title="My current plan"
      action={
        <Link className={linkClass} to={PLAN_PATH}>
          View <span className="sr-only">my plan</span>
        </Link>
      }
    >
      <QueryBody query={query} loadingLabel="Loading your plan" errorTitle="We couldn't load your plan">
        {(subscriptions) => {
          const [primary] = primaryFirst(subscriptions)
          if (!primary) {
            return <EmptyState icon={<Smartphone className="size-8" />} title="No plan on your account" />
          }
          return (
            <dl className="grid gap-3 sm:grid-cols-2">
              <Field label="Plan">{primary.plan.name}</Field>
              <Field label="Price">{perMonth(primary.plan.monthlyRental)}</Field>
              <Field label="Number">{primary.msisdn}</Field>
              <Field label="Status">
                <StatusBadge kind="subscription" value={primary.status} />
              </Field>
            </dl>
          )
        }}
      </QueryBody>
    </DashboardCard>
  )
}

export function PlanCard() {
  return (
    <Gated allOf={['plan.read']}>
      <PlanCardBody />
    </Gated>
  )
}

/** Quick links (CUS-01.4), each one only with the permissions its page needs (frontend.md §2). */
export function QuickLinks() {
  const links: { to: string; label: string; allOf: Permission[] }[] = [
    { to: CREATE_REQUEST_PATH, label: 'Create request', allOf: ['case.read', 'case.create', 'customer.profile.read'] },
    { to: PAY_BILL_PATH, label: 'Pay now', allOf: ['payment.read', 'payment.initiate'] },
    { to: PAYMENT_HISTORY_PATH, label: 'Payment history', allOf: ['payment.read'] },
    { to: PROMOTIONS_PATH, label: 'Promotions', allOf: ['promotion.read'] },
  ]
  return (
    <DashboardCard title="Quick links">
      <ul className="flex flex-col gap-2">
        {links.map((link) => (
          <Can key={link.to} allOf={link.allOf}>
            <li>
              <Link className={linkClass} to={link.to}>
                {link.label}
              </Link>
            </li>
          </Can>
        ))}
      </ul>
    </DashboardCard>
  )
}

/** Recent requests (E11 `size=3`, default 3-month window) with "View all" (CUS-01.1, CUS-01.4). */
function RecentRequestsBody() {
  const query = useQuery({
    queryKey: casesQueryKey(RECENT_CASES_QUERY),
    queryFn: () => fetchCases(RECENT_CASES_QUERY),
    staleTime: LIST_STALE_TIME_MS,
    meta: OWN_FORBIDDEN_HANDLING,
  })
  return (
    <DashboardCard
      title="Recent requests"
      action={
        <Link className={linkClass} to={REQUESTS_PATH}>
          View all <span className="sr-only">requests</span>
        </Link>
      }
    >
      <QueryBody query={query} loadingLabel="Loading recent requests" errorTitle="We couldn't load your requests">
        {(page) =>
          page.items.length === 0 ? (
            <EmptyState icon={<Inbox className="size-8" />} title="No requests in the last 3 months" />
          ) : (
            <ul className="flex flex-col divide-y">
              {page.items.slice(0, DASHBOARD_CARD_SIZE).map((item) => (
                <li key={item.reference} className="flex flex-wrap items-center justify-between gap-2 py-2">
                  <div className="flex flex-col">
                    <span className="font-medium text-foreground">{item.subject}</span>
                    <span className="text-sm text-muted-foreground">
                      {item.reference} · {formatDateTime(item.createdAt)}
                    </span>
                  </div>
                  <StatusBadge kind="case" value={item.status} />
                </li>
              ))}
            </ul>
          )
        }
      </QueryBody>
    </DashboardCard>
  )
}

export function RecentRequestsCard() {
  return (
    <Gated allOf={['case.read']}>
      <RecentRequestsBody />
    </Gated>
  )
}

/** The newest own payment (E14 `size=1`), its own loading/empty/error inside the bills card. */
function LastPayment() {
  const query = useQuery({
    queryKey: paymentsQueryKey(LAST_PAYMENT_QUERY),
    queryFn: () => fetchPayments(LAST_PAYMENT_QUERY),
    staleTime: LIST_STALE_TIME_MS,
    meta: OWN_FORBIDDEN_HANDLING,
  })
  return (
    <div className="flex flex-col gap-2 border-t pt-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="font-semibold text-foreground">Last payment</h3>
        <Link className={linkClass} to={PAYMENT_HISTORY_PATH}>
          Payment history
        </Link>
      </div>
      <QueryBody query={query} loadingLabel="Loading your last payment" errorTitle="We couldn't load your last payment">
        {(page) => {
          const [last] = page.items
          if (!last) return <p className="text-muted-foreground">No payments in the last 3 months.</p>
          return (
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex flex-col">
                <Link className={linkClass} to={paymentStatusPath(last.reference)}>
                  {last.reference}
                </Link>
                <span className="text-sm text-muted-foreground">
                  {formatInr(last.amount)} · {formatDateTime(last.initiatedAt)}
                </span>
              </div>
              <StatusBadge kind="payment" value={last.status} />
            </div>
          )
        }}
      </QueryBody>
    </div>
  )
}

/**
 * Bills (E13 `status=OPEN`): each open invoice (≤ 3) with the server's `outstanding`; no total and no arithmetic (F8). "Pay now"
 * with `payment.initiate` and at least one invoice where `isPositiveMoney(outstanding)` (frontend.md §3 Dashboard).
 */
function BillsCardBody() {
  const query = useQuery({
    queryKey: openInvoicesQueryKey,
    queryFn: fetchOpenInvoices,
    staleTime: LIST_STALE_TIME_MS,
    meta: OWN_FORBIDDEN_HANDLING,
  })
  const payable = query.data?.some((invoice) => isPositiveMoney(invoice.outstanding)) ?? false
  return (
    <DashboardCard
      title="Bills"
      className="md:col-span-2"
      action={
        payable ? (
          <Can allOf={['payment.initiate']}>
            <Button asChild>
              <Link to={PAY_BILL_PATH}>
                <Wallet aria-hidden="true" className="size-4" />
                Pay now
              </Link>
            </Button>
          </Can>
        ) : null
      }
    >
      <QueryBody query={query} loadingLabel="Loading your bills" errorTitle="We couldn't load your bills">
        {(invoices) =>
          invoices.length === 0 ? (
            <EmptyState icon={<Receipt className="size-8" />} title="No open bills" description="You're all paid up." />
          ) : (
            <ul className="flex flex-col divide-y">
              {invoices.slice(0, DASHBOARD_CARD_SIZE).map((invoice) => (
                <li key={invoice.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
                  <div className="flex flex-col">
                    <span className="font-medium text-foreground">{invoice.invoiceRef}</span>
                    <span className="text-sm text-muted-foreground">Due {formatDate(invoice.dueDate)}</span>
                  </div>
                  <span className="text-foreground">
                    <span className="text-sm text-muted-foreground">Outstanding </span>
                    {formatInr(invoice.outstanding)}
                  </span>
                </li>
              ))}
            </ul>
          )
        }
      </QueryBody>
      <LastPayment />
    </DashboardCard>
  )
}

export function BillsCard() {
  return (
    <Gated allOf={['payment.read']}>
      <BillsCardBody />
    </Gated>
  )
}
