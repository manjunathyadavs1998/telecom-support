import { useQueryClient } from '@tanstack/react-query'
import { Plus, Search } from 'lucide-react'
import { useCallback, useMemo, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useNavigate } from 'react-router'
import { StatusBadge } from '@/components/data/StatusBadge'
import { PageContainer } from '@/components/layout/PageContainer'
import { ErrorState } from '@/components/states/ErrorState'
import { LoadingState } from '@/components/states/LoadingState'
import { Button } from '@/components/ui/button'
import { formatDateTime } from '@/lib/format'
import { suspensionReasonText } from '@/lib/inboundText'
import { cn } from '@/lib/utils'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { Can } from '@/permissions/Can'
import type { Permission } from '@/types/api'
import { useInboundParams } from '../useInboundParams'
import { VerifyPanel } from '../verification/VerifyPanel'
import { markVerificationLost, useCustomerMatch, type CachedCheck } from '../verificationState'
import {
  minutesLeft,
  useAgentProfileQuery,
  useCurrentCheckQuery,
  VerifiedCustomerContext,
  WARN_AT_MINUTES,
  type VerifiedCustomer,
} from './queries'
import { ReverifyDialog } from './ReverifyDialog'
import { VerifiedBadge } from './VerifiedBadge'

const TABS: readonly { to: string; label: string; requires: readonly Permission[]; end?: boolean }[] = [
  { to: '', label: 'Overview', requires: [], end: true },
  { to: 'billing', label: 'Billing', requires: ['payment.read'] },
  { to: 'cases', label: 'Cases', requires: ['case.read'] },
  { to: 'reactivation', label: 'Reactivation', requires: ['customer.profile.read'] },
]

/**
 * The `inbound-customer` layout route (03-inbound/frontend.md §2, §3.3): validated `:customerId`, then the frame keyed by it so
 * a different customer never inherits another's state.
 */
export function WorkspaceFrame() {
  const params = useInboundParams()
  if (params === null) return <NotFoundPage />
  return <Frame key={params.customerId} customerId={params.customerId} />
}

function Frame({ customerId }: { customerId: number }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const rootRef = useRef<HTMLDivElement>(null)
  const checkQuery = useCurrentCheckQuery(customerId)
  const check = checkQuery.data
  const verified = check?.status === 'VERIFIED'

  // "Lost" (dialog over the mounted page) only after this frame has seen a VERIFIED check; a first visit verifies in place.
  const [everVerified, setEverVerified] = useState(false)
  if (verified && !everVerified) setEverVerified(true)
  const lost = everVerified && check === null
  const [voluntary, setVoluntary] = useState(false)
  const [reverifiedCount, setReverifiedCount] = useState(0)
  const [dirtyKeys, setDirtyKeys] = useState<ReadonlySet<string>>(() => new Set())

  const offset = check?.serverOffsetMs ?? 0
  const serverNow = useCallback(() => Date.now() + offset, [offset])
  const profile = useAgentProfileQuery(customerId, verified)
  const { data: match } = useCustomerMatch(customerId)

  const setDirty = useCallback((key: string, dirty: boolean) => {
    setDirtyKeys((keys) => {
      if (keys.has(key) === dirty) return keys
      const next = new Set(keys)
      if (dirty) next.add(key)
      else next.delete(key)
      return next
    })
  }, [])

  const context = useMemo<VerifiedCustomer>(
    () => ({ customerId, verified, serverNow, reverifiedCount, setDirty }),
    [customerId, verified, serverNow, reverifiedCount, setDirty],
  )

  // At 0 the frame refetches I4 once (§3.3): 404 → verification lost; 200 → the new check is the baseline (no loop).
  const onElapsed = useCallback(() => {
    void checkQuery.refetch().then((result) => {
      if (result.data === null) markVerificationLost(queryClient, customerId)
    })
  }, [checkQuery, queryClient, customerId])

  const focusHeading = () => {
    requestAnimationFrame(() => rootRef.current?.querySelector<HTMLElement>('h1')?.focus())
  }

  const onReverified = (_check: CachedCheck) => {
    setVoluntary(false)
    setReverifiedCount((n) => n + 1)
  }

  const left = verified && check.validUntil ? minutesLeft(check.validUntil, serverNow) : 0
  const p = verified ? profile.data : undefined

  let body
  if (checkQuery.isPending) {
    body = <LoadingState label="Checking verification" />
  } else if (checkQuery.isError) {
    body = <ErrorState title="We couldn't check the verification" error={checkQuery.error} onRetry={() => void checkQuery.refetch()} />
  } else if (!verified && !lost) {
    body = <VerifyPanel customerId={customerId} onVerified={focusHeading} />
  } else {
    body = <Outlet />
  }

  return (
    <VerifiedCustomerContext.Provider value={context}>
      <div ref={rootRef}>
        <PageContainer
          title="Customer workspace"
          actions={
            <Button asChild variant="outline">
              <Link to="/inbound">
                <Search aria-hidden="true" className="size-4" />
                New search
              </Link>
            </Button>
          }
        >
          <section aria-label="Customer" className="flex flex-col gap-3 rounded-lg border bg-white p-4 md:p-6">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="flex flex-col gap-1">
                <p className="text-lg font-semibold text-foreground">{p?.fullName ?? match?.maskedName ?? 'Customer'}</p>
                <p className="text-sm text-muted-foreground">
                  Customer {p?.customerRef ?? match?.customerRef ?? '…'}
                  {p ? ` · Account ${p.accountRef}` : null}
                </p>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                {verified && check.validUntil ? (
                  <VerifiedBadge validUntil={check.validUntil} serverNow={serverNow} onElapsed={onElapsed} />
                ) : null}
                {verified && left > 0 && left <= WARN_AT_MINUTES ? (
                  <Button type="button" size="sm" variant="outline" onClick={() => setVoluntary(true)}>
                    Verify again
                  </Button>
                ) : null}
              </div>
            </div>
            {p || match ? (
              <dl className="flex flex-wrap gap-x-6 gap-y-2 text-sm">
                <div className="flex items-center gap-2">
                  <dt className="text-muted-foreground">Customer</dt>
                  <dd>
                    <StatusBadge kind="customer" value={p?.customerStatus ?? match!.customerStatus} />
                  </dd>
                </div>
                <div className="flex items-center gap-2">
                  <dt className="text-muted-foreground">Account</dt>
                  <dd>
                    <StatusBadge kind="account" value={p?.accountStatus ?? match!.accountStatus} />
                  </dd>
                </div>
                {p?.suspension ? (
                  <div className="flex items-center gap-2">
                    <dt className="text-muted-foreground">Suspended</dt>
                    <dd>
                      {suspensionReasonText(p.suspension.reason)} since {formatDateTime(p.suspension.since)}
                    </dd>
                  </div>
                ) : null}
              </dl>
            ) : null}
          </section>

          {verified || lost ? (
            <div className="flex flex-wrap items-end justify-between gap-3 border-b">
              <nav aria-label="Customer workspace" className="-mb-px flex overflow-x-auto">
                {TABS.map((tab) => (
                  <Can key={tab.label} allOf={tab.requires}>
                    <NavLink
                      to={tab.to === '' ? `/inbound/customers/${customerId}` : `/inbound/customers/${customerId}/${tab.to}`}
                      end={tab.end}
                      className={({ isActive }) =>
                        cn(
                          'whitespace-nowrap border-b-2 px-4 py-2 text-sm font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                          isActive
                            ? 'border-primary text-foreground'
                            : 'border-transparent text-muted-foreground hover:text-foreground',
                        )
                      }
                    >
                      {tab.label}
                    </NavLink>
                  </Can>
                ))}
              </nav>
              <Can allOf={['case.create.onbehalf', 'customer.profile.read', 'plan.read', 'case.read']}>
                <Button asChild size="sm" className="mb-2">
                  <Link to={`/inbound/customers/${customerId}/cases/new`}>
                    <Plus aria-hidden="true" className="size-4" />
                    Raise request
                  </Link>
                </Button>
              </Can>
            </div>
          ) : null}

          {body}
        </PageContainer>
      </div>
      <ReverifyDialog
        open={lost || voluntary}
        customerId={customerId}
        dirty={dirtyKeys.size > 0}
        required={lost}
        onVerified={onReverified}
        onCancel={() => {
          if (lost) void navigate('/inbound')
          else setVoluntary(false)
        }}
      />
    </VerifiedCustomerContext.Provider>
  )
}

export { WorkspaceFrame as Component }
