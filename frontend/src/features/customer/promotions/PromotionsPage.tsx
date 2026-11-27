import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { ChevronDown, Tag } from 'lucide-react'
import { useEffect, useId, useState } from 'react'
import { useLocation } from 'react-router'
import { PageContainer } from '@/components/layout/PageContainer'
import { EmptyState } from '@/components/states/EmptyState'
import { ErrorState } from '@/components/states/ErrorState'
import { LoadingState } from '@/components/states/LoadingState'
import { SectionErrorState } from '@/components/states/SectionErrorState'
import { Button } from '@/components/ui/button'
import { subscriptionsQuery } from '@/features/customer/plan/queries'
import { formatDateTime } from '@/lib/format'
import { cn } from '@/lib/utils'
import { ForbiddenPage } from '@/pages/ForbiddenPage'
import { Can } from '@/permissions/Can'
import { usePermissions } from '@/permissions/usePermissions'
import { ApiError } from '@/services/api/errors'
import type { Promotion, Subscription } from '@/types/api'
import { ClaimDialog, NUMBERS_ERROR_TITLE } from './ClaimDialog'
import { numberEnding, usePromotionsQuery } from './queries'

/** The card heading's id: the focus target for `/customer/promotions#<code>` (frontend.md §3 Promotions, F18). */
const headingIdFor = (code: string) => `promotion-${code}-title`

function TermsDisclosure({ terms }: { terms: string }) {
  const [expanded, setExpanded] = useState(false)
  const termsId = useId()
  return (
    <div className="flex flex-col gap-2">
      <Button
        type="button"
        variant="outline"
        size="sm"
        className="self-start"
        aria-expanded={expanded}
        aria-controls={termsId}
        onClick={() => setExpanded((open) => !open)}
      >
        Terms
        <ChevronDown aria-hidden="true" className={cn('size-4 transition-transform', expanded && 'rotate-180')} />
      </Button>
      <p id={termsId} hidden={!expanded} className="text-sm whitespace-pre-line text-foreground">
        {terms}
      </p>
    </div>
  )
}

type PromotionCardProps = {
  promotion: Promotion
  subscriptions: UseQueryResult<Subscription[]>
}

/**
 * One offer (frontend.md §3 Promotions; CUS-04.1/04.2): title, short description, "Valid until" (IST; `validTo` is an exclusive
 * end, F23), eligibility, a "Terms" disclosure, the numbers that already claimed it (§10 D3), and "View & claim" with
 * `promotion.claim` + `plan.read`.
 */
function PromotionCard({ promotion, subscriptions }: PromotionCardProps) {
  const headingId = headingIdFor(promotion.code)
  const byId = new Map((subscriptions.data ?? []).map((subscription) => [subscription.id, subscription]))
  const claimedNumbers = promotion.claimedSubscriptionIds
    .map((id) => byId.get(id)?.msisdn)
    .filter((msisdn): msisdn is string => Boolean(msisdn))
  return (
    <section
      id={promotion.code}
      aria-labelledby={headingId}
      className="flex scroll-mt-4 flex-col gap-4 rounded-lg border bg-white p-4 md:p-6"
    >
      <div className="flex flex-col gap-1">
        <h2
          id={headingId}
          tabIndex={-1}
          className="rounded-sm text-lg font-semibold text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          {promotion.title}
        </h2>
        <p className="text-foreground">{promotion.shortDescription}</p>
      </div>
      <dl className="grid gap-3 sm:grid-cols-2">
        <div className="flex flex-col gap-0.5">
          <dt className="text-sm text-muted-foreground">Valid until</dt>
          <dd className="text-foreground">{formatDateTime(promotion.validTo)}</dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-sm text-muted-foreground">Eligibility</dt>
          <dd className="text-foreground">{promotion.eligibility}</dd>
        </div>
      </dl>
      <TermsDisclosure terms={promotion.terms} />
      {claimedNumbers.length > 0 ? (
        <ul className="flex flex-col gap-0.5 text-sm text-success">
          {claimedNumbers.map((msisdn) => (
            <li key={msisdn}>Claimed for {numberEnding(msisdn)}</li>
          ))}
        </ul>
      ) : null}
      <Can allOf={['promotion.claim', 'plan.read']}>
        <ClaimDialog promotion={promotion} subscriptions={subscriptions} />
      </Can>
    </section>
  )
}

/** `/customer/promotions#<code>` (dashboard banner): focus that card's heading after the shell's route-change `<h1>` focus. */
function useFocusPromotionFromHash(promotions: readonly Promotion[] | undefined) {
  const { hash } = useLocation()
  const [handled, setHandled] = useState<string | null>(null)
  useEffect(() => {
    if (!promotions || !hash || handled === hash) return
    const code = decodeURIComponent(hash.slice(1))
    if (!promotions.some((promotion) => promotion.code === code)) return
    // A macrotask runs after this commit's effects, including the shell's (parent) route-change focus on the <h1> (F18).
    const timer = setTimeout(() => {
      document.getElementById(headingIdFor(code))?.focus()
      setHandled(hash)
    }, 0)
    return () => clearTimeout(timer)
  }, [hash, promotions, handled])
}

/**
 * Promotions `/customer/promotions` (frontend.md §3 Promotions; CUS-04): the eligible active offers (E8) with per-number claim
 * state. E5 supplies the MSISDN labels (only with `plan.read`). A 403 on the promotions query (the page's primary query) →
 * `ForbiddenPage`; any other error → `ErrorState` with retry.
 */
export function PromotionsPage() {
  const query = usePromotionsQuery()
  const { can } = usePermissions()
  const subscriptions = useQuery({ ...subscriptionsQuery, enabled: can(['plan.read']) })
  useFocusPromotionFromHash(query.data)

  if (query.isError && query.error instanceof ApiError && query.error.status === 403) return <ForbiddenPage />
  return (
    <PageContainer title="Promotions" description="Offers available on your numbers.">
      {query.isPending ? (
        <LoadingState label="Loading offers" />
      ) : query.isError ? (
        <ErrorState title="We couldn't load offers" error={query.error} onRetry={() => void query.refetch()} />
      ) : query.data.length === 0 ? (
        <EmptyState
          icon={<Tag className="size-8" />}
          title="No offers for you right now"
          description="New offers for your plan will appear here."
        />
      ) : (
        <div className="flex flex-col gap-6">
          {subscriptions.isError ? (
            // Without E5 the "Claimed for number ending …" lines can't be shown; say so instead of hiding them (FC8).
            <section aria-label="Your numbers" className="rounded-lg border bg-white">
              <SectionErrorState
                title={NUMBERS_ERROR_TITLE}
                error={subscriptions.error}
                onRetry={() => void subscriptions.refetch()}
              />
            </section>
          ) : null}
          <div className="grid gap-6 xl:grid-cols-2">
            {query.data.map((promotion) => (
              <PromotionCard key={promotion.code} promotion={promotion} subscriptions={subscriptions} />
            ))}
          </div>
        </div>
      )}
    </PageContainer>
  )
}

/** `route.lazy` entry (pageRegistry). */
export { PromotionsPage as Component }
