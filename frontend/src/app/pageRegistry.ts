import type { ComponentType } from 'react'

/** What a `route.lazy` page module resolves to (React Router data mode). */
export type LazyPage = () => Promise<{ Component: ComponentType }>

/**
 * Nav item / child id → its page, loaded with `route.lazy` (frontend.md §2, NFR-PERF), so each page is its own chunk.
 * The route guard renders before a lazy page mounts, so a forbidden user never fires its API calls. An id without an
 * entry renders `ComingSoonPage`. Each page task (F9–F20) adds its entry here.
 */
export const PAGE_REGISTRY: Readonly<Partial<Record<string, LazyPage>>> = {
  'customer-home': () => import('@/features/customer/dashboard/DashboardPage'),
  'customer-profile': () => import('@/features/customer/profile/ProfilePage'),
  'customer-plan': () => import('@/features/customer/plan/PlanPage'),
  'customer-promotions': () => import('@/features/customer/promotions/PromotionsPage'),
  'customer-requests': () => import('@/features/customer/requests/RequestListPage'),
  'customer-request-new': () => import('@/features/customer/requests/CreateRequestPage'),
  'customer-payments': () => import('@/features/customer/payments/PaymentHistoryPage'),
  'customer-payment-pay': () => import('@/features/customer/payments/PayBillPage'),
  'customer-payment-status': () => import('@/features/customer/payments/PaymentStatusPage'),
  'customer-payment-receipt': () => import('@/features/customer/payments/ReceiptPage'),
  'inbound-search': () => import('@/features/inbound/search/CustomerSearchPage'),
  'inbound-customer': () => import('@/features/inbound/workspace/OverviewPage'),
  'inbound-billing': () => import('@/features/inbound/billing/BillingPage'),
  'inbound-cases': () => import('@/features/inbound/cases/CaseListPage'),
  'inbound-case': () => import('@/features/inbound/cases/CaseDetailPage'),
  'inbound-case-new': () => import('@/features/inbound/cases/RaiseRequestPage'),
  'inbound-reactivation': () => import('@/features/inbound/reactivation/ReactivationPage'),
  'outbound-queue': () => import('@/features/outbound/queue/QueuePage'),
  'outbound-case': () => import('@/features/outbound/case/CaseDetailPage'),
}

/**
 * `NavChild.layout` id → its layout component (03-inbound/frontend.md §2), which renders `<Outlet/>` for the child's page and
 * every page nested under it. An id without an entry renders the pages bare.
 */
export const LAYOUT_REGISTRY: Readonly<Partial<Record<string, LazyPage>>> = {
  'inbound-workspace': () => import('@/features/inbound/workspace/WorkspaceFrame'),
}
