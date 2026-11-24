import {
  BarChart3,
  Bookmark,
  CreditCard,
  FileSearch,
  Gift,
  Home,
  LayoutList,
  type LucideIcon,
  MessageSquareText,
  Search,
  Smartphone,
  UserRound,
} from 'lucide-react'
import { P, type Permission } from '@/permissions/permissions'

export type NavItem = { to: string; label: string; icon: LucideIcon; permission: Permission; end?: boolean }
export type NavSection = { title: string; items: NavItem[] }

/**
 * Role-aware navigation (XC-01, BRD §16 application shell). An item shows only when the user holds its
 * permission; the same permission guards the route in router.tsx.
 */
export const navigation: NavSection[] = [
  {
    title: 'My account',
    items: [
      { to: '/customer', label: 'Home', icon: Home, permission: P.CUSTOMER_PROFILE_UPDATE, end: true },
      { to: '/customer/profile', label: 'Profile', icon: UserRound, permission: P.CUSTOMER_PROFILE_UPDATE },
      { to: '/customer/plan', label: 'My Plan', icon: Smartphone, permission: P.PLAN_CHANGE },
      { to: '/customer/promotions', label: 'Promotions', icon: Gift, permission: P.PROMOTION_CLAIM },
      { to: '/customer/requests', label: 'Service Requests', icon: MessageSquareText, permission: P.CASE_CREATE },
      { to: '/customer/payments', label: 'Payment History', icon: CreditCard, permission: P.PAYMENT_INITIATE },
    ],
  },
  {
    title: 'Inbound',
    items: [{ to: '/inbound', label: 'Customer Search', icon: Search, permission: P.CUSTOMER_LOOKUP, end: true }],
  },
  {
    title: 'Outbound',
    items: [
      { to: '/outbound', label: 'Case Queue', icon: LayoutList, permission: P.CASE_READ_QUEUE, end: true },
      { to: '/outbound/bookmarks', label: 'Bookmarks', icon: Bookmark, permission: P.CASE_BOOKMARK },
    ],
  },
  {
    title: 'Business',
    items: [
      { to: '/business', label: 'Analytics', icon: BarChart3, permission: P.ANALYTICS_READ, end: true },
      { to: '/business/audit', label: 'Audit & Export', icon: FileSearch, permission: P.AUDIT_READ },
    ],
  },
]

export function visibleNavigation(permissions: readonly string[]): NavSection[] {
  return navigation
    .map((s) => ({ ...s, items: s.items.filter((i) => permissions.includes(i.permission)) }))
    .filter((s) => s.items.length > 0)
}
