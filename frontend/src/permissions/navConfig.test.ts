import { describe, expect, it } from 'vitest'
import { meFixtures } from '@/test/fixtures/me'
import type { Me } from '@/types/api'
import { landingFor, NAV_ITEMS, ROUTE_PARAM_PATTERNS, visibleNav } from './navConfig'

type Group = { heading: string | null; labels: string[] }

// frontend.md §2 "Resulting nav and landing per role combination" (backend.md §3.1 seed users), transcribed from the design.
const EXPECTED: { user: keyof typeof meFixtures; nav: Group[]; landing: string }[] = [
  {
    user: 'customer',
    nav: [{ heading: null, labels: ['Home', 'Profile', 'My plan', 'Promotions', 'Service requests', 'Payment history'] }],
    landing: '/customer',
  },
  { user: 'IN001', nav: [{ heading: null, labels: ['Customer search'] }], landing: '/inbound' },
  { user: 'IN002', nav: [{ heading: null, labels: ['Customer search'] }], landing: '/inbound' },
  {
    user: 'OUT001',
    nav: [
      { heading: 'Outbound', labels: ['Case queue', 'Bookmarks'] },
      { heading: 'Operations', labels: ['Analytics'] },
    ],
    landing: '/outbound',
  },
  {
    user: 'SUP001',
    nav: [
      { heading: 'Outbound', labels: ['Case queue', 'Bookmarks'] },
      { heading: 'Operations', labels: ['Analytics'] },
    ],
    landing: '/outbound',
  },
  { user: 'BUS001', nav: [{ heading: null, labels: ['Analytics', 'Audit & export'] }], landing: '/analytics' },
  { user: 'AUD001', nav: [{ heading: null, labels: ['Analytics', 'Audit & export'] }], landing: '/analytics' },
]

describe('navConfig registry (frontend.md §2, §10)', () => {
  it('gives every item a non-empty requires, a unique path, a unique id and a BRD id', () => {
    for (const item of NAV_ITEMS) {
      expect(item.requires.length, item.id).toBeGreaterThan(0)
      expect(item.brd, item.id).toMatch(/^(CUS|INB|OUT|BUS)-\d{2}/)
    }
    expect(new Set(NAV_ITEMS.map((item) => item.path)).size).toBe(NAV_ITEMS.length)
    expect(new Set(NAV_ITEMS.map((item) => item.id)).size).toBe(NAV_ITEMS.length)
  })

  it('marks every customer-portal item userType CUSTOMER and no other item', () => {
    for (const item of NAV_ITEMS) {
      expect(item.userType === 'CUSTOMER', item.path).toBe(item.path.startsWith('/customer'))
    }
  })

  it('holds exactly the §2 route set', () => {
    expect(NAV_ITEMS.map((item) => item.path).sort()).toEqual(
      [
        '/customer',
        '/customer/profile',
        '/customer/plan',
        '/customer/promotions',
        '/customer/requests',
        '/customer/payments',
        '/inbound',
        '/outbound',
        '/outbound/bookmarks',
        '/analytics',
        '/audit',
      ].sort(),
    )
  })
})

describe('navConfig child routes (frontend.md §2 route table; XC-02, BR-10)', () => {
  const children = NAV_ITEMS.flatMap((item) => (item.children ?? []).map((child) => ({ item, child })))

  it('holds exactly the §2 child routes, each under its nav item and customer-only through it', () => {
    expect(children.map(({ item, child }) => [item.id, child.path])).toEqual([
      ['customer-requests', '/customer/requests/new'],
      ['customer-payments', '/customer/payments/pay'],
      ['customer-payments', '/customer/payments/:reference'],
      ['customer-payments', '/customer/payments/:reference/receipt'],
    ])
    for (const { item, child } of children) {
      expect(child.path.startsWith(`${item.path}/`), child.id).toBe(true)
      expect(item.userType, child.id).toBe('CUSTOMER')
    }
  })

  it('gives every route (nav items and children) a unique id and path', () => {
    const ids = [...NAV_ITEMS.map((item) => item.id), ...children.map(({ child }) => child.id)]
    const paths = [...NAV_ITEMS.map((item) => item.path), ...children.map(({ child }) => child.path)]
    expect(new Set(ids).size).toBe(ids.length)
    expect(new Set(paths).size).toBe(paths.length)
  })

  it('adds the §2 extra permissions on top of the inherited ones', () => {
    const requires = Object.fromEntries(children.map(({ item, child }) => [child.path, [...item.requires, ...(child.requires ?? [])].sort()]))
    expect(requires).toEqual({
      '/customer/requests/new': ['case.create', 'case.read', 'customer.profile.read'],
      '/customer/payments/pay': ['payment.initiate', 'payment.read'],
      '/customer/payments/:reference': ['payment.read'],
      '/customer/payments/:reference/receipt': ['payment.read'],
    })
  })

  it('labels the crumbs, the payment status one with its reference', () => {
    const labels = children.map(({ child }) =>
      typeof child.label === 'function' ? child.label({ reference: 'PAY-20261125-000007' }) : child.label,
    )
    expect(labels).toEqual(['New request', 'Pay bill', 'PAY-20261125-000007', 'Receipt'])
  })

  it.each([
    ['PAY-20261125-000007', true],
    ['PAY-20261125-1234567', true],
    ['PAY-20261125-00007', false],
    ['pay-20261125-000007', false],
    ['PAY-2026112-000007', false],
    ['PAY-20261125-000007x', false],
    ['pay', false],
  ])('the :reference pattern on %s is %s', (reference, ok) => {
    expect(ROUTE_PARAM_PATTERNS.reference?.test(reference)).toBe(ok)
  })
})

describe('visibleNav and landingFor per role combination (frontend.md §2 table)', () => {
  it('covers all 7 seed role combinations', () => {
    expect(EXPECTED.map((row) => row.user).sort()).toEqual(Object.keys(meFixtures).sort())
  })

  it.each(EXPECTED)('$user sees exactly its §2 nav and lands on $landing', ({ user, nav, landing }) => {
    const me = meFixtures[user]
    expect(visibleNav(me).map((group) => ({ heading: group.heading, labels: group.items.map((item) => item.label) }))).toEqual(nav)
    expect(landingFor(me)).toBe(landing)
  })

  it('hides the customer portal from a STAFF user holding the customer read permissions', () => {
    const staff = meFixtures.IN001
    expect(staff.permissions).toEqual(expect.arrayContaining(['customer.profile.read', 'plan.read', 'case.read', 'payment.read']))
    expect(visibleNav(staff).flatMap((group) => group.items).filter((item) => item.userType === 'CUSTOMER')).toEqual([])
  })

  it('gives no nav and no landing to a signed-out user or one without any page permission', () => {
    const none: Me = { ...meFixtures.IN001, permissions: ['service.reactivate'] }
    expect(visibleNav(null)).toEqual([])
    expect(landingFor(null)).toBeNull()
    expect(visibleNav(none)).toEqual([])
    expect(landingFor(none)).toBeNull()
  })
})
