import { Link } from 'react-router'
import { ErrorState } from '@/components/states/ErrorState'
import { Button } from '@/components/ui/button'
import type { ApiError } from '@/services/api/errors'
import { PAYMENT_HISTORY_PATH } from './paymentFormat'

export const PAYMENT_NOT_FOUND_TITLE = "We couldn't find this payment"

/**
 * A 404 on a payment detail (status or receipt): frontend.md §3 "A 404 on a detail → `ErrorState` 'not found' with a back
 * link" (review FC5). No retry: the reference is unknown or belongs to another customer.
 */
export function PaymentNotFound({ error }: { error: ApiError }) {
  return (
    <div className="flex flex-col items-center gap-2">
      <ErrorState title={PAYMENT_NOT_FOUND_TITLE} error={error} />
      <Button asChild variant="outline">
        <Link to={PAYMENT_HISTORY_PATH}>Payment history</Link>
      </Button>
    </div>
  )
}
