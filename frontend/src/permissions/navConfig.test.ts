import { describe, expect, it } from 'vitest'
import { meFixtures } from '@/test/fixtures/me'
import type { Me } from '@/types/api'
import { landingFor, NAV_ITEMS, visibleNav } from './navConfig'

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
