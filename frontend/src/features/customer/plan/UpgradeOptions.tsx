import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { DataTable, type DataTableColumn } from '@/components/data/DataTable'
import { ErrorState } from '@/components/states/ErrorState'
import { FormRootError } from '@/components/states/FormRootError'
import { LoadingState } from '@/components/states/LoadingState'
import { Button } from '@/components/ui/button'
import { formatDate, formatInr } from '@/lib/format'
import type { ApiError } from '@/services/api/errors'
import { useIdempotencyKey } from '@/services/api/idempotency'
import type { PlanOption, PlanSummary, Subscription } from '@/types/api'
import { PlanChangeDialog } from './PlanChangeDialog'
import { perMonth } from './planFormat'
import { planChangePath, SUBSCRIPTIONS_QUERY_KEY, usePlanOptionsQuery } from './queries'
import { KeyBenefits } from './SubscriptionCard'

export const NO_UPGRADES_MESSAGE = 'No upgrades are available for this plan'
export const OPTIONS_REFRESHED_MESSAGE = 'Options were refreshed.'

/** A comparison row: the current plan, then each quoted option (frontend.md §3 My plan, CUS-03.4). */
type Row = { kind: 'current'; plan: PlanSummary } | { kind: 'option'; option: PlanOption }

const planOf = (row: Row) => (row.kind === 'current' ? row.plan : row.option.plan)
const dash = <span className="text-muted-foreground">—</span>

function columns(onChoose: (option: PlanOption) => void): DataTableColumn<Row>[] {
  return [
    {
      id: 'plan',
      header: 'Plan',
      cell: (row) => (
        <span className="font-medium">
          {planOf(row).name}
          {row.kind === 'current' ? <span className="font-normal text-muted-foreground"> (current)</span> : null}
        </span>
      ),
    },
    { id: 'rental', header: 'Monthly rental', cell: (row) => perMonth(planOf(row).monthlyRental) },
    {
      id: 'difference',
      header: 'Difference',
      cell: (row) => (row.kind === 'option' ? `+${formatInr(row.option.rentalDifference)}` : dash),
    },
    { id: 'benefits', header: 'Key benefits', cell: (row) => <KeyBenefits benefits={planOf(row).benefits} /> },
    {
      id: 'effective',
      header: 'Effective date',
      cell: (row) => (row.kind === 'option' ? formatDate(row.option.effectiveDate) : dash),
    },
    { id: 'charge', header: 'Charge', cell: (row) => (row.kind === 'option' ? formatInr(row.option.charge) : dash) },
    {
      id: 'action',
      header: <span className="sr-only">Action</span>,
      mobileLabel: 'Action',
      cell: (row) =>
        row.kind === 'option' ? (
          <Button size="sm" onClick={() => onChoose(row.option)} aria-label={`Choose ${row.option.plan.name}`}>
            Choose
          </Button>
        ) : (
          dash
        ),
    },
  ]
}

/**
 * Upgrade options for one ACTIVE subscription without a pending change (frontend.md §3 My plan; CUS-03.4/03.5): "See upgrade
 * options" fetches E6 on demand → a comparison table → `PlanChangeDialog`. The idempotency key lives here, scoped to this
 * subscription's E7 path, so it survives closing and reopening the dialog (kept on 409/5xx, dropped on 2xx/4xx). A business 422
 * shows the server's `detail` + "Options were refreshed." and refetches the quotes.
 */
export function UpgradeOptions({ subscription }: { subscription: Subscription }) {
  const [requested, setRequested] = useState(false)
  const [chosen, setChosen] = useState<PlanOption | null>(null)
  const [rejected, setRejected] = useState<ApiError | null>(null)
  const [rejectedFocus, setRejectedFocus] = useState(0)
  const rejectedRef = useRef<HTMLDivElement>(null)
  /** After a 422 the focus goes to the error, not back to the "Choose" button the closing dialog would return it to. */
  const focusRejectedOnClose = useRef(false)
  const query = usePlanOptionsQuery(subscription.id, requested)
  const idempotency = useIdempotencyKey('PLAN_CHANGE', planChangePath(subscription.id))
  const client = useQueryClient()

  useEffect(() => {
    if (rejectedFocus > 0) rejectedRef.current?.focus()
  }, [rejectedFocus])

  function onQuoteRejected(error: ApiError) {
    focusRejectedOnClose.current = true
    setRejected(error)
    setRejectedFocus((n) => n + 1)
    void query.refetch()
    // The reason may be a change already scheduled or a suspended number, both held by E5 (review FC7).
    void client.invalidateQueries({ queryKey: SUBSCRIPTIONS_QUERY_KEY, exact: true })
  }

  if (!requested) {
    return (
      <Button variant="outline" className="self-start" onClick={() => setRequested(true)}>
        See upgrade options
      </Button>
    )
  }

  return (
    <div className="flex flex-col gap-3 border-t pt-4">
      <h3 className="font-semibold text-foreground">
        Upgrade options
      </h3>
      {rejected ? (
        <FormRootError
          ref={rejectedRef}
          message={`${rejected.message} ${OPTIONS_REFRESHED_MESSAGE}`}
          error={rejected}
        />
      ) : null}
      {query.isPending ? (
        <LoadingState label="Loading upgrade options" />
      ) : query.isError ? (
        <ErrorState
          title="We couldn't load upgrade options"
          error={query.error}
          onRetry={() => void query.refetch()}
        />
      ) : query.data.length === 0 ? (
        <p className="text-muted-foreground">{NO_UPGRADES_MESSAGE}</p>
      ) : (
        <DataTable
          caption={`Upgrade options for ${subscription.msisdn}`}
          columns={columns((option) => {
            setRejected(null)
            setChosen(option)
          })}
          rows={[
            { kind: 'current', plan: subscription.plan },
            ...query.data.map((option): Row => ({ kind: 'option', option })),
          ]}
          rowKey={(row) => (row.kind === 'current' ? `current-${row.plan.code}` : row.option.plan.code)}
          busy={query.isFetching}
        />
      )}
      <PlanChangeDialog
        key={chosen ? `${chosen.plan.code}|${chosen.effectiveDate}|${chosen.charge}` : 'closed'}
        subscription={subscription}
        option={chosen}
        idempotency={idempotency}
        onClose={() => setChosen(null)}
        onQuoteRejected={onQuoteRejected}
        onCloseAutoFocus={(event) => {
          if (!focusRejectedOnClose.current) return
          focusRejectedOnClose.current = false
          event.preventDefault()
          rejectedRef.current?.focus()
        }}
      />
    </div>
  )
}
