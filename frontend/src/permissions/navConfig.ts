import {
  Bookmark,
  ChartColumn,
  CreditCard,
  House,
  ListTodo,
  MessageSquare,
  Search,
  ShieldCheck,
  Smartphone,
  Tag,
  User,
  type LucideIcon,
} from 'lucide-react'
import type { Params } from 'react-router'
import type { Me, Permission } from '@/types/api'

export type Section = 'customer' | 'inbound' | 'outbound' | 'operations' | 'business'

/** A breadcrumb / page label: fixed, or built from the route params (e.g. the payment reference). */
export type RouteLabel = string | ((params: Params) => string)

/**
 * A non-nav protected route under a nav item (frontend.md §2). `path` is absolute. It inherits the parent's `requires` and
 * `userType`; its own `requires` are added (all-of). A child whose path extends another child's path nests under it (receipt
 * under payment status), so it also inherits that child's gate and crumb.
 */
export type NavChild = {
  id: string
  path: string
  label: RouteLabel
  requires?: readonly Permission[]
  /**
   * Layout route (03-inbound/frontend.md §2): id in `LAYOUT_REGISTRY` whose component wraps this child's page and every child
   * nested under it (`<Outlet/>`), so it survives navigation between them. Without a registry entry the pages render bare.
   */
  layout?: string
  /**
   * Route param patterns (03-inbound/frontend.md §9 FE1). They apply across the whole nav item's tree and win over the global
   * `ROUTE_PARAM_PATTERNS`, because a parent route's `useParams` also sees its children's params.
   */
  params?: Readonly<Record<string, RegExp>>
}

export type NavItem = {
  id: string
  label: string
  icon: LucideIcon
  path: string
  /** A fixed section, or one chosen from the user's permissions (Analytics: Operations or Business). */
  section: Section | ((permissions: ReadonlySet<Permission>) => Section)
  /** All-of; never empty. */
  requires: readonly Permission[]
  /** Customer portal gate (frontend.md §2 planner decision 1): UX only, the server enforces every API. */
  userType?: 'CUSTOMER'
  brd: string
  slice: '02' | '03' | '04' | '05'
  /** Child routes (no nav entry); the nav item stays active (`aria-current`) on them. */
  children?: readonly NavChild[]
}

export type NavGroup = {
  section: Section
  /** `null` when the user has only one section (headings show only for > 1 section). */
  heading: string | null
  items: NavItem[]
}

/** Render order and heading texts (frontend.md §2). */
export const SECTION_ORDER: readonly Section[] = ['customer', 'inbound', 'outbound', 'operations', 'business']
export const SECTION_HEADINGS: Record<Section, string> = {
  customer: 'Customer',
  inbound: 'Inbound',
  outbound: 'Outbound',
  operations: 'Operations',
  business: 'Business',
}

/** Inbound workspace params (03-inbound/frontend.md §2, §9 FE1); also parsed by `useInboundParams()`. */
export const INBOUND_PARAM_PATTERNS = {
  customerId: /^\d{1,18}$/,
  reference: /^SR-\d{8}-\d{6,}$/,
} as const satisfies Readonly<Record<string, RegExp>>

/**
 * The one registry behind the sidebar and the protected routes (frontend.md §2, XC-01, NFR-MAINT), so a nav entry and its
 * route guard can never disagree. Order within a section is the sidebar order.
 */
export const NAV_ITEMS: readonly NavItem[] = [
  { id: 'customer-home', label: 'Home', icon: House, path: '/customer', section: 'customer', requires: ['customer.profile.read'], userType: 'CUSTOMER', brd: 'CUS-01', slice: '02' },
  { id: 'customer-profile', label: 'Profile', icon: User, path: '/customer/profile', section: 'customer', requires: ['customer.profile.read'], userType: 'CUSTOMER', brd: 'CUS-02', slice: '02' },
  { id: 'customer-plan', label: 'My plan', icon: Smartphone, path: '/customer/plan', section: 'customer', requires: ['plan.read'], userType: 'CUSTOMER', brd: 'CUS-03', slice: '02' },
  { id: 'customer-promotions', label: 'Promotions', icon: Tag, path: '/customer/promotions', section: 'customer', requires: ['promotion.read'], userType: 'CUSTOMER', brd: 'CUS-04', slice: '02' },
  {
    id: 'customer-requests',
    label: 'Service requests',
    icon: MessageSquare,
    path: '/customer/requests',
    section: 'customer',
    requires: ['case.read'],
    userType: 'CUSTOMER',
    brd: 'CUS-05/06',
    slice: '02',
    children: [{ id: 'customer-request-new', path: '/customer/requests/new', label: 'New request', requires: ['case.create', 'customer.profile.read'] }],
  },
  {
    id: 'customer-payments',
    label: 'Payment history',
    icon: CreditCard,
    path: '/customer/payments',
    section: 'customer',
    requires: ['payment.read'],
    userType: 'CUSTOMER',
    brd: 'CUS-07/08',
    slice: '02',
    children: [
      { id: 'customer-payment-pay', path: '/customer/payments/pay', label: 'Pay bill', requires: ['payment.initiate'] },
      { id: 'customer-payment-status', path: '/customer/payments/:reference', label: (params) => params.reference ?? '' },
      { id: 'customer-payment-receipt', path: '/customer/payments/:reference/receipt', label: 'Receipt' },
    ],
  },
  {
    id: 'inbound-search',
    label: 'Customer search',
    icon: Search,
    path: '/inbound',
    section: 'inbound',
    requires: ['customer.lookup'],
    brd: 'INB-01..04',
    slice: '03',
    // 03-inbound/frontend.md §2: the workspace is a layout route; billing, cases and reactivation nest under it.
    children: [
      { id: 'inbound-customer', path: '/inbound/customers/:customerId', label: 'Customer', requires: ['customer.verify'], layout: 'inbound-workspace', params: { customerId: INBOUND_PARAM_PATTERNS.customerId } },
      { id: 'inbound-billing', path: '/inbound/customers/:customerId/billing', label: 'Billing', requires: ['payment.read'] },
      { id: 'inbound-cases', path: '/inbound/customers/:customerId/cases', label: 'Cases', requires: ['case.read'] },
      { id: 'inbound-case-new', path: '/inbound/customers/:customerId/cases/new', label: 'New request', requires: ['case.create.onbehalf', 'customer.profile.read', 'plan.read'] },
      { id: 'inbound-case', path: '/inbound/customers/:customerId/cases/:reference', label: (params) => params.reference ?? '', params: { reference: INBOUND_PARAM_PATTERNS.reference } },
      { id: 'inbound-reactivation', path: '/inbound/customers/:customerId/reactivation', label: 'Reactivation', requires: ['customer.profile.read'] },
    ],
  },
  {
    id: 'outbound-queue',
    label: 'Case queue',
    icon: ListTodo,
    path: '/outbound',
    section: 'outbound',
    requires: ['case.read.queue'],
    brd: 'OUT-01..05',
    slice: '04',
    // 04-outbound/frontend.md §2: case detail; the reference pattern is the inbound one (SR-…).
    children: [
      { id: 'outbound-case', path: '/outbound/cases/:reference', label: (params) => params.reference ?? '', params: { reference: INBOUND_PARAM_PATTERNS.reference } },
    ],
  },
  // 04 §2: the server needs case.read.queue + case.bookmark for O10–O13.
  { id: 'outbound-bookmarks', label: 'Bookmarks', icon: Bookmark, path: '/outbound/bookmarks', section: 'outbound', requires: ['case.read.queue', 'case.bookmark'], brd: 'OUT-06', slice: '04' },
  {
    id: 'analytics',
    label: 'Analytics',
    icon: ChartColumn,
    path: '/analytics',
    // Outbound "operational view" (BRD §8, OQ 2): a holder of `case.read.queue` sees it under Operations.
    section: (permissions) => (permissions.has('case.read.queue') ? 'operations' : 'business'),
    requires: ['analytics.read'],
    brd: 'BUS-01/02',
    slice: '05',
  },
  { id: 'audit', label: 'Audit & export', icon: ShieldCheck, path: '/audit', section: 'business', requires: ['audit.read'], brd: 'BUS-03', slice: '05' },
]

/**
 * Route params that must match a pattern (frontend.md §2): a mismatch renders `NotFoundPage` before any page mounts, so no
 * API call is made.
 */
export const ROUTE_PARAM_PATTERNS: Readonly<Record<string, RegExp>> = {
  reference: /^PAY-\d{8}-\d{6,}$/,
}

/** The param patterns of `item`'s routes: the global ones overridden by what its children declare (§9 FE1). */
export function paramPatternsOf(item: NavItem): Readonly<Record<string, RegExp>> {
  return Object.assign({}, ROUTE_PARAM_PATTERNS, ...(item.children ?? []).map((child) => child.params ?? {}))
}

/** True if `me` may open `item` (UI gating only; the server enforces every API, BR-10). */
export function canOpen(me: Me | null | undefined, item: NavItem): boolean {
  if (!me) return false
  if (item.userType !== undefined && item.userType !== me.userType) return false
  return item.requires.every((permission) => me.permissions.includes(permission))
}

export function sectionOf(item: NavItem, permissions: ReadonlySet<Permission>): Section {
  return typeof item.section === 'function' ? item.section(permissions) : item.section
}

/** The user's sidebar: visible items grouped by section in §2 order, headings only when there is more than one section. */
export function visibleNav(me: Me | null | undefined): NavGroup[] {
  if (!me) return []
  const permissions: ReadonlySet<Permission> = new Set(me.permissions)
  const bySection = new Map<Section, NavItem[]>()
  for (const item of NAV_ITEMS) {
    if (!canOpen(me, item)) continue
    const section = sectionOf(item, permissions)
    bySection.set(section, [...(bySection.get(section) ?? []), item])
  }
  const sections = SECTION_ORDER.filter((section) => bySection.has(section))
  return sections.map((section) => ({
    section,
    heading: sections.length > 1 ? SECTION_HEADINGS[section] : null,
    items: bySection.get(section) ?? [],
  }))
}

/** The landing route (§2 `/`): the first visible nav item, or `null` when the user has none (→ `NoAccessPage`). */
export function landingFor(me: Me | null | undefined): string | null {
  return visibleNav(me)[0]?.items[0]?.path ?? null
}
