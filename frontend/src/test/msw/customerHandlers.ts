import { http, HttpResponse } from 'msw'
import type { ProblemDetail } from '@/types/api'
import {
  caseCategories,
  caseCreated,
  casePage,
  contactVerification,
  openInvoices,
  paymentInitiated,
  paymentPage,
  paymentsByStatus,
  planChangeScheduled,
  planOptions,
  problem,
  profile,
  profileVerified,
  promotionClaim,
  promotions,
  receipt,
  subscriptions,
} from '@/test/fixtures/customer'

// Default slice 02 handlers for E1–E17 (02-customer/frontend.md §8); tests override per case with `server.use(...)`.

const ME = '*/api/v1/customers/me'

/** An RFC 7807 response (00-overview §4) with the correlation id header. */
export function problemResponse(body: ProblemDetail, headers: Record<string, string> = {}) {
  return HttpResponse.json(body, {
    status: body.status,
    headers: { 'Content-Type': 'application/problem+json', 'X-Correlation-Id': body.correlationId, ...headers },
  })
}

function pageParams(request: Request): { page: number; size: number } {
  const url = new URL(request.url)
  return { page: Number(url.searchParams.get('page') ?? 0), size: Number(url.searchParams.get('size') ?? 20) }
}

export const customerHandlers = [
  // E1, E2
  http.get(`${ME}/profile`, () => HttpResponse.json(profile)),
  http.patch(`${ME}/profile`, async ({ request }) => {
    const body = (await request.json()) as { aliasName: string | null; version: number }
    if (body.version !== profile.version) return problemResponse(problem(409, 'conflict', 'The profile was changed.'))
    return HttpResponse.json({ ...profile, aliasName: body.aliasName, version: profile.version + 1 })
  }),
  // E3, E4
  http.post(`${ME}/contact-verifications`, () => HttpResponse.json(contactVerification, { status: 201 })),
  http.post(`${ME}/contact-verifications/:verificationId/confirm`, () => HttpResponse.json(profileVerified)),
  // E5, E6, E7
  http.get(`${ME}/subscriptions`, () => HttpResponse.json(subscriptions)),
  http.get(`${ME}/subscriptions/:subscriptionId/plan-options`, () => HttpResponse.json(planOptions)),
  http.post(`${ME}/subscriptions/:subscriptionId/plan-changes`, () =>
    HttpResponse.json(planChangeScheduled, { status: 201 }),
  ),
  // E8, E9
  http.get(`${ME}/promotions`, () => HttpResponse.json(promotions)),
  http.post(`${ME}/promotions/:promotionCode/claims`, () => HttpResponse.json(promotionClaim, { status: 201 })),
  // E10, E11, E12
  http.get('*/api/v1/case-categories', () => HttpResponse.json(caseCategories)),
  http.get(`${ME}/cases`, ({ request }) => {
    const { page, size } = pageParams(request)
    return HttpResponse.json(casePage(undefined, page, size))
  }),
  http.post(`${ME}/cases`, () => HttpResponse.json(caseCreated, { status: 201 })),
  // E13, E14, E15
  http.get(`${ME}/invoices`, () => HttpResponse.json(openInvoices)),
  http.get(`${ME}/payments`, ({ request }) => {
    const { page, size } = pageParams(request)
    return HttpResponse.json(paymentPage(undefined, page, size))
  }),
  http.post(`${ME}/payments`, () => HttpResponse.json(paymentInitiated, { status: 201 })),
  // E16, E17
  http.get(`${ME}/payments/:reference`, ({ params }) => {
    const payment = Object.values(paymentsByStatus).find((p) => p.reference === params.reference)
    return payment ? HttpResponse.json(payment) : problemResponse(problem(404, 'not-found', 'Payment not found.'))
  }),
  http.get(`${ME}/payments/:reference/receipt`, ({ params }) =>
    params.reference === receipt.paymentReference
      ? HttpResponse.json(receipt)
      : problemResponse(problem(422, 'receipt-unavailable', 'A receipt is available only for successful payments.')),
  ),
]
