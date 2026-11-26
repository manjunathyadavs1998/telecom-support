import type { Payment } from '@/types/api'

export type PaymentMethod = NonNullable<Payment['method']>

const METHOD_LABELS: Record<PaymentMethod, string> = {
  UPI: 'UPI',
  CARD: 'Card',
  NETBANKING: 'Net banking',
  WALLET: 'Wallet',
}

/** The customer-facing payment method (CUS-07.1); "—" while the provider has not reported one (frontend.md §3 Payment history). */
export function paymentMethodLabel(method: Payment['method']): string {
  if (!method) return '—'
  return METHOD_LABELS[method] ?? method
}

/** The payment status page (gateway return) and the receipt of one payment (frontend.md §2). */
export const paymentStatusPath = (reference: string) => `/customer/payments/${encodeURIComponent(reference)}`
export const paymentReceiptPath = (reference: string) => `${paymentStatusPath(reference)}/receipt`
export const PAYMENT_HISTORY_PATH = '/customer/payments'
export const PAY_BILL_PATH = '/customer/payments/pay'
