import type {
  CaseCreated,
  CasePage,
  CaseSummary,
  Category,
  ContactVerification,
  Invoice,
  Payment,
  PaymentInitiated,
  PaymentPage,
  PaymentStatus,
  PlanChange,
  PlanOption,
  PlanSummary,
  ProblemDetail,
  Profile,
  Promotion,
  PromotionClaim,
  Receipt,
  Subscription,
  VerificationProblem,
} from '@/types/api'

// Slice 02 customer fixtures (02-customer/frontend.md §8): one per state the screens render. Values follow the seed customer
// 1 (`+919800000001`). Tests copy and override them (`{ ...fixture, field }`) rather than mutating them.

// E1/E2/E4 Profile
export const profile: Profile = {
  customerRef: 'CUS-000001',
  accountRef: 'ACC-000001',
  fullName: 'Customer One',
  aliasName: null,
  primaryMsisdn: '+919800000001',
  secondaryMsisdn: null,
  secondaryMsisdnVerified: false,
  email: null,
  emailVerified: false,
  billingAddress: {
    line1: '12 MG Road',
    line2: null,
    city: 'Bengaluru',
    state: 'Karnataka',
    postalCode: '560001',
    country: 'IN',
  },
  version: 3,
}

export const profileVerified: Profile = {
  ...profile,
  aliasName: 'Ravi',
  secondaryMsisdn: '+919811112222',
  secondaryMsisdnVerified: true,
  email: 'ravi@example.com',
  emailVerified: true,
  version: 4,
}

// E3
export const contactVerification: ContactVerification = {
  id: 501,
  channel: 'EMAIL',
  maskedDestination: 'ra***@ex***.com',
  expiresAt: '2026-11-25T10:10:00Z',
}

// E5 Subscriptions and E6 plan options
export const planUnlimited499: PlanSummary = {
  code: 'POST499',
  name: 'Unlimited 499',
  planType: 'POSTPAID',
  monthlyRental: '499.00',
  description: 'Unlimited calls, 75 GB data',
  benefits: [
    { type: 'VOICE', label: 'Calls', value: 'Unlimited', key: true },
    { type: 'DATA', label: 'Data', value: '75 GB', key: true },
    { type: 'SMS', label: 'SMS', value: '100/day', key: false },
    { type: 'OTT', label: 'Streaming', value: 'Basic pack', key: false },
  ],
}

export const planUnlimited799: PlanSummary = {
  code: 'POST799',
  name: 'Unlimited 799',
  planType: 'POSTPAID',
  monthlyRental: '799.00',
  description: 'Unlimited calls, 150 GB data, roaming',
  benefits: [
    { type: 'VOICE', label: 'Calls', value: 'Unlimited', key: true },
    { type: 'DATA', label: 'Data', value: '150 GB', key: true },
    { type: 'ROAMING', label: 'Roaming', value: 'Included', key: false },
  ],
}

export const pendingPlanChange: PlanChange = {
  id: 301,
  fromPlanCode: 'POST499',
  toPlanCode: 'POST799',
  effectiveDate: '2026-12-01',
  charge: '0.00',
  status: 'SCHEDULED',
}

export const subscriptionPrimary: Subscription = {
  id: 101,
  msisdn: '+919800000001',
  primary: true,
  status: 'ACTIVE',
  billingCycleDay: 1,
  plan: planUnlimited499,
  pendingPlanChange: null,
}

export const subscriptionSecondary: Subscription = {
  id: 102,
  msisdn: '+919800001234',
  primary: false,
  status: 'ACTIVE',
  billingCycleDay: 1,
  plan: planUnlimited499,
  pendingPlanChange: null,
}

export const subscriptionWithPendingChange: Subscription = { ...subscriptionPrimary, pendingPlanChange }

export const subscriptionSuspended: Subscription = { ...subscriptionSecondary, status: 'SUSPENDED' }

export const subscriptions: Subscription[] = [subscriptionPrimary]

export const planOptions: PlanOption[] = [
  { plan: planUnlimited799, rentalDifference: '300.00', effectiveDate: '2026-12-01', charge: '0.00' },
]

// E7
export const planChangeScheduled: PlanChange = pendingPlanChange

// E8/E9 Promotions
const TERMS_HASH = 'a'.repeat(64)

export const promotionClaimable: Promotion = {
  code: 'DATA10',
  title: '10 GB extra data',
  shortDescription: 'Get 10 GB extra data for 30 days.',
  eligibility: 'Postpaid plans of ₹499 and above',
  terms: 'Valid once per number. Data expires after 30 days.',
  termsHash: TERMS_HASH,
  validFrom: '2026-11-01T00:00:00Z',
  validTo: '2026-12-31T18:30:00Z',
  claimed: false,
  eligibleSubscriptionIds: [101],
  claimedSubscriptionIds: [],
}

export const promotionPartlyClaimed: Promotion = {
  ...promotionClaimable,
  code: 'OTT3M',
  title: '3 months of streaming',
  claimed: true,
  eligibleSubscriptionIds: [102],
  claimedSubscriptionIds: [101],
}

export const promotionFullyClaimed: Promotion = {
  ...promotionClaimable,
  code: 'ROAM50',
  title: '50% off roaming',
  claimed: true,
  eligibleSubscriptionIds: [],
  claimedSubscriptionIds: [101],
}

export const promotions: Promotion[] = [promotionClaimable]

export const promotionClaim: PromotionClaim = {
  promotionCode: 'DATA10',
  subscriptionId: 101,
  status: 'CLAIMED',
  claimedAt: '2026-11-25T10:00:00Z',
}

// E10 Categories
export const categoryWithSubcategories: Category = {
  code: 'BILLING',
  name: 'Billing',
  subcategories: [
    { code: 'BILLING_WRONG_CHARGE', name: 'Wrong charge' },
    { code: 'BILLING_REFUND', name: 'Refund' },
  ],
}

export const categoryWithoutSubcategories: Category = { code: 'GENERAL', name: 'General enquiry', subcategories: [] }

export const caseCategories: Category[] = [categoryWithSubcategories, categoryWithoutSubcategories]

// E11/E12 Service requests
export const caseSummary: CaseSummary = {
  reference: 'SR-20261125-000042',
  categoryName: 'Billing',
  subcategoryName: 'Wrong charge',
  subject: 'Charged twice for November',
  createdAt: '2026-11-25T10:00:00Z',
  severity: 'MEDIUM',
  status: 'SUBMITTED',
}

export function casePage(items: CaseSummary[] = [caseSummary], page = 0, size = 20, totalItems = items.length): CasePage {
  return { items, page, size, totalItems, totalPages: Math.ceil(totalItems / size) }
}

export const emptyCasePage: CasePage = casePage([], 0, 20, 0)

export const caseCreated: CaseCreated = {
  reference: 'SR-20261126-000043',
  caseType: 'REQUEST',
  status: 'SUBMITTED',
  severity: 'LOW',
  createdAt: '2026-11-26T05:00:00Z',
  contactMsisdn: '+919800000001',
  accountRef: 'ACC-000001',
}

// E13 Invoices (status=OPEN by default)
export const invoiceOpenOlder: Invoice = {
  id: 801,
  invoiceRef: 'INV-202610-000001',
  periodStart: '2026-10-01',
  periodEnd: '2026-10-31',
  issueDate: '2026-11-01',
  dueDate: '2026-11-15',
  amountDue: '589.82',
  amountPaid: '0.00',
  outstanding: '589.82',
  status: 'OPEN',
}

export const invoiceOpenNewer: Invoice = {
  id: 802,
  invoiceRef: 'INV-202611-000002',
  periodStart: '2026-11-01',
  periodEnd: '2026-11-30',
  issueDate: '2026-12-01',
  dueDate: '2026-12-15',
  amountDue: '1234.50',
  amountPaid: '234.50',
  outstanding: '1000.00',
  status: 'OPEN',
}

export const invoicePaid: Invoice = {
  ...invoiceOpenOlder,
  id: 800,
  invoiceRef: 'INV-202609-000000',
  amountPaid: '589.82',
  outstanding: '0.00',
  status: 'PAID',
}

export const openInvoices: Invoice[] = [invoiceOpenOlder]

// E14/E16 Payments, one per status
const paymentBase: Payment = {
  reference: 'PAY-20261125-000007',
  invoiceRef: invoiceOpenOlder.invoiceRef,
  amount: '589.82',
  currency: 'INR',
  method: null,
  status: 'PENDING',
  failureReason: null,
  initiatedAt: '2026-11-25T10:00:00Z',
  completedAt: null,
  receiptAvailable: false,
}

export const paymentPending: Payment = paymentBase

export const paymentSucceeded: Payment = {
  ...paymentBase,
  reference: 'PAY-20261125-000008',
  method: 'UPI',
  status: 'SUCCEEDED',
  completedAt: '2026-11-25T10:02:00Z',
  receiptAvailable: true,
}

export const paymentFailed: Payment = {
  ...paymentBase,
  reference: 'PAY-20261125-000009',
  status: 'FAILED',
  failureReason: 'gateway_unavailable',
  completedAt: '2026-11-25T10:01:00Z',
}

export const paymentExpired: Payment = {
  ...paymentBase,
  reference: 'PAY-20261125-000010',
  status: 'EXPIRED',
  completedAt: '2026-11-25T10:30:00Z',
}

export const paymentReview: Payment = { ...paymentBase, reference: 'PAY-20261125-000011', status: 'REVIEW' }

export const paymentsByStatus: Record<PaymentStatus, Payment> = {
  PENDING: paymentPending,
  SUCCEEDED: paymentSucceeded,
  FAILED: paymentFailed,
  EXPIRED: paymentExpired,
  REVIEW: paymentReview,
}

export function paymentPage(
  items: Payment[] = [paymentSucceeded, paymentPending],
  page = 0,
  size = 20,
  totalItems = items.length,
): PaymentPage {
  return { items, page, size, totalItems, totalPages: Math.ceil(totalItems / size) }
}

export const emptyPaymentPage: PaymentPage = paymentPage([], 0, 20, 0)

// E15
export const paymentInitiated: PaymentInitiated = {
  reference: paymentPending.reference,
  status: 'PENDING',
  redirectUrl: `/mock-gateway/checkout/${paymentPending.reference}`,
}

// E17
export const receipt: Receipt = {
  paymentReference: paymentSucceeded.reference,
  invoiceRef: paymentSucceeded.invoiceRef,
  accountRef: 'ACC-000001',
  customerName: 'Customer One',
  amount: paymentSucceeded.amount,
  currency: 'INR',
  method: 'UPI',
  gatewayTransactionId: 'MOCK-TXN-000008',
  paidAt: '2026-11-25T10:02:00Z',
}

// Errors (00-overview §4)
const PROBLEM_BASE = 'https://telecom.example/problems/'

export function problem(status: number, type: string, detail?: string, extra: Partial<ProblemDetail> = {}): ProblemDetail {
  return {
    type: `${PROBLEM_BASE}${type}`,
    title: type,
    status,
    ...(detail === undefined ? {} : { detail }),
    correlationId: `corr-${status}-${type}`,
    ...extra,
  }
}

export function verificationProblem(attemptsRemaining: number): VerificationProblem {
  return { ...problem(422, 'verification-failed', 'The code is not correct.'), attemptsRemaining }
}
