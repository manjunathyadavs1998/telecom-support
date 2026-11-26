import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query'
import type { ListApiQuery } from '@/components/data/useListParams'
import { api, apiClient } from '@/services/api/client'
import type { Invoice, Payment, PaymentInitiated, PaymentInitiateRequest, PaymentPage } from '@/types/api'

/** Own payments (frontend.md §5): `['customer','payments',params]`; a terminal payment status invalidates the whole prefix. */
export const PAYMENTS_QUERY_KEY = ['customer', 'payments'] as const
export const paymentsQueryKey = (query: ListApiQuery) => [...PAYMENTS_QUERY_KEY, query] as const

/** Lists use a 30 s `staleTime` (frontend.md §5). */
const LIST_STALE_TIME_MS = 30_000

/**
 * E14 `GET /customers/me/payments` (CUS-07, BR-05/06): own payments, newest first. Without `from`/`to` the server applies the
 * last 3 months. There is no reference search on this list. `page` is 0-based here.
 */
export async function fetchPayments({ from, to, page, size }: ListApiQuery): Promise<PaymentPage> {
  const query = { ...(from && to ? { from, to } : {}), page, size }
  return api.call(apiClient.GET('/api/v1/customers/me/payments', { params: { query } }))
}

/**
 * One page of payments; the previous page stays on screen while the next one loads (`keepPreviousData`). Refetched on window
 * focus, so a status the server reconciled meanwhile shows up (CUS-07.5).
 */
export function usePaymentsQuery(query: ListApiQuery) {
  return useQuery({
    queryKey: paymentsQueryKey(query),
    queryFn: () => fetchPayments(query),
    staleTime: LIST_STALE_TIME_MS,
    placeholderData: keepPreviousData,
    refetchOnWindowFocus: 'always',
  })
}

/** Own invoices (frontend.md §5): `['customer','invoices',status]`. */
export const INVOICES_QUERY_KEY = ['customer', 'invoices'] as const
export const openInvoicesQueryKey = [...INVOICES_QUERY_KEY, 'OPEN'] as const

/** E13 `GET /customers/me/invoices?status=OPEN` (CUS-08.1): each open invoice with the server's `outstanding`. */
export async function fetchOpenInvoices(): Promise<Invoice[]> {
  return api.call(apiClient.GET('/api/v1/customers/me/invoices', { params: { query: { status: 'OPEN' } } }))
}

export function useOpenInvoicesQuery() {
  return useQuery({ queryKey: openInvoicesQueryKey, queryFn: fetchOpenInvoices, staleTime: LIST_STALE_TIME_MS })
}

/** One own payment (frontend.md §5): `['customer','payment',ref]`. */
export const paymentQueryKey = (reference: string) => ['customer', 'payment', reference] as const

/** E16 `GET /customers/me/payments/{reference}` (CUS-08.3). */
export async function fetchPayment(reference: string): Promise<Payment> {
  return api.call(apiClient.GET('/api/v1/customers/me/payments/{reference}', { params: { path: { reference } } }))
}

/** E16 for the payment a stored E15 record points to (abandoned checkout, frontend.md §3 Pay bill); idle without a reference. */
export function useStoredPaymentQuery(reference: string | undefined) {
  return useQuery({
    queryKey: paymentQueryKey(reference ?? ''),
    queryFn: () => fetchPayment(reference!),
    enabled: reference !== undefined,
  })
}

/** The E15 path, also the persisted idempotency record's path (frontend.md §3 Pay bill, §4). */
export const PAYMENT_INITIATE_PATH = '/customers/me/payments'

export type InitiatePaymentVariables = { idempotencyKey: string; body: PaymentInitiateRequest }

/** E15 `POST /customers/me/payments` (CUS-08.1/08.2/08.4, NFR-REL): the full outstanding amount of one own invoice. */
export async function postPayment({ idempotencyKey, body }: InitiatePaymentVariables): Promise<PaymentInitiated> {
  return api.call(
    apiClient.POST('/api/v1/customers/me/payments', { params: { header: { 'Idempotency-Key': idempotencyKey } }, body }),
  )
}

/** Initiate: never optimistic (XC-07); the result is never taken from the browser return (the status page polls E16). */
export function useInitiatePayment() {
  return useMutation({ mutationFn: postPayment })
}
