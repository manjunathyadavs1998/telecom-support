import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { axe } from 'vitest-axe'
import { Sidebar } from '@/components/layout/Sidebar'
import { useUiStore } from '@/stores/uiStore'
import { meFixtures } from '@/test/fixtures/me'
import type { Me } from '@/types/api'
import { landingFor, visibleNav } from './navConfig'

/** frontend.md §2 "Resulting nav and landing per role combination": [heading | null, labels][] and the landing route. */
const CUSTOMER_NAV = [[null, ['Home', 'Profile', 'My plan', 'Promotions', 'Service requests', 'Payment history']]]
const INBOUND_NAV = [[null, ['Customer search']]]
const OUTBOUND_NAV = [
  ['Outbound', ['Case queue', 'Bookmarks']],
  ['Operations', ['Analytics']],
]
const BUSINESS_NAV = [[null, ['Analytics', 'Audit & export']]]
const EXPECTED: [keyof typeof meFixtures, unknown, string][] = [
  ['customer', CUSTOMER_NAV, '/customer'],
  ['IN001', INBOUND_NAV, '/inbound'],
  ['IN002', INBOUND_NAV, '/inbound'],
  ['OUT001', OUTBOUND_NAV, '/outbound'],
  ['SUP001', OUTBOUND_NAV, '/outbound'],
  ['BUS001', BUSINESS_NAV, '/analytics'],
  ['AUD001', BUSINESS_NAV, '/analytics'],
]

function renderSidebar(me: Me, { at = landingFor(me) ?? '/', collapsed = false } = {}) {
  return render(
    <MemoryRouter initialEntries={[at]}>
      <Sidebar groups={visibleNav(me)} collapsed={collapsed} />
    </MemoryRouter>,
  )
}

/** What the sidebar shows: each list's heading (its accessible name, or null) and its link labels, in DOM order. */
function renderedNav() {
  const nav = screen.getByRole('navigation', { name: 'Main' })
  return within(nav)
    .getAllByRole('list')
    .map((list) => {
      const headingId = list.getAttribute('aria-labelledby')
      const heading = headingId ? (document.getElementById(headingId)?.textContent ?? '') : null
      return [heading, within(list).getAllByRole('link').map((link) => link.textContent)]
    })
}

describe('sidebar per role combination (frontend.md §2; XC-01)', () => {
  it.each(EXPECTED)('%s: items, order, headings and landing match the §2 table', (user, expectedNav, landing) => {
    const me = meFixtures[user]
    expect(landingFor(me)).toBe(landing)
    renderSidebar(me)
    expect(renderedNav()).toEqual(expectedNav)
  })

  it('a STAFF user holding plan.read (IN001) sees no customer item', () => {
    expect(meFixtures.IN001.permissions).toContain('plan.read')
    renderSidebar(meFixtures.IN001)
    const hrefs = screen.getAllByRole('link').map((link) => link.getAttribute('href'))
    expect(hrefs.filter((href) => href?.startsWith('/customer'))).toEqual([])
  })

  it('the active link has aria-current="page"; a parent route link is not active on its sub-page', () => {
    renderSidebar(meFixtures.customer, { at: '/customer/profile' })
    expect(screen.getByRole('link', { name: 'Profile' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Home' })).not.toHaveAttribute('aria-current')
  })

  it('collapsed icon rail: labels stay the accessible names, headings stay list names, focus shows a tooltip', async () => {
    const user = userEvent.setup()
    renderSidebar(meFixtures.OUT001, { collapsed: true })
    expect(renderedNav()).toEqual(OUTBOUND_NAV)
    await user.tab()
    expect(screen.getByRole('link', { name: 'Case queue' })).toHaveFocus()
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Case queue')
  })

  it.each([false, true])('collapsed=%s: axe clean', async (collapsed) => {
    const { container } = renderSidebar(meFixtures.OUT001, { collapsed })
    expect(await axe(container)).toHaveNoViolations()
  })
})

describe('useUiStore (frontend.md §5)', () => {
  beforeEach(() => {
    localStorage.clear()
    useUiStore.setState({ sidebarCollapsed: null, mobileNavOpen: false })
  })

  it('persists only sidebarCollapsed under the tcs-ui key', () => {
    useUiStore.getState().setSidebarCollapsed(true)
    useUiStore.getState().setMobileNavOpen(true)
    const stored = JSON.parse(localStorage.getItem('tcs-ui') ?? '{}') as { state: Record<string, unknown> }
    expect(stored.state).toEqual({ sidebarCollapsed: true })
  })

  it('reads a v0 "never collapsed" (false) as no choice, so md–lg keeps its icon-rail default', async () => {
    localStorage.setItem('tcs-ui', JSON.stringify({ state: { sidebarCollapsed: false }, version: 0 }))
    await useUiStore.persist.rehydrate()
    expect(useUiStore.getState().sidebarCollapsed).toBeNull()
    localStorage.setItem('tcs-ui', JSON.stringify({ state: { sidebarCollapsed: true }, version: 0 }))
    await useUiStore.persist.rehydrate()
    expect(useUiStore.getState().sidebarCollapsed).toBe(true)
  })
})
