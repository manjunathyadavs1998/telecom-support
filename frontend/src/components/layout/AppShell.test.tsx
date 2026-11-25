import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { axe } from 'vitest-axe'
import { registerNavigator } from '@/app/navigation'
import { ME_QUERY_KEY, queryClient } from '@/app/queryClient'
import { registerRouter } from '@/app/router'
import { appRoutes } from '@/app/routes'
import { resetCsrfBootstrap } from '@/features/authentication/meQuery'
import { landingFor } from '@/permissions/navConfig'
import { useUiStore } from '@/stores/uiStore'
import { meFixtures } from '@/test/fixtures/me'
import { clearXsrfCookie } from '@/test/msw/handlers'
import { server } from '@/test/msw/server'
import type { Me } from '@/types/api'
import { AppShell } from './AppShell'

let unregister: (() => void) | null = null

/** The app's routes at `path` with `/me` already cached (fresh, so the loader makes no request). */
function renderShell(me: Me, path = landingFor(me) ?? '/') {
  queryClient.setQueryData(ME_QUERY_KEY, me)
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  unregister = registerRouter(router)
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, ...view }
}

const h1 = (name: string) => screen.findByRole('heading', { level: 1, name })
const menuButton = () => screen.getByRole('button', { name: 'Open navigation menu' })

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  localStorage.clear()
  useUiStore.setState({ sidebarCollapsed: null, mobileNavOpen: false })
  resetCsrfBootstrap()
})
afterEach(() => {
  cleanup() // unmount first: clearing the cache under a mounted useMeQuery would start a re-bootstrap
  unregister?.()
  unregister = null
  server.resetHandlers()
  clearXsrfCookie()
  queryClient.clear()
})
afterAll(() => server.close())

describe('AppShell keyboard and focus (frontend.md §8; XC-08, NFR-A11Y)', () => {
  it('the skip link is the first tab stop and Enter moves focus to <main>', async () => {
    const user = userEvent.setup()
    renderShell(meFixtures.customer)
    await h1('Home')
    await user.tab()
    const skip = screen.getByRole('link', { name: 'Skip to main content' })
    expect(skip).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('main')).toHaveFocus()
  })

  it('navigating moves focus to the new page <h1>, updates the title and the breadcrumb', async () => {
    const user = userEvent.setup()
    renderShell(meFixtures.customer)
    await h1('Home')
    expect(document.title).toBe('Home · Telecom Support')

    await user.click(screen.getByRole('link', { name: 'Profile' }))
    const heading = await h1('Profile')
    await waitFor(() => expect(heading).toHaveFocus())
    expect(document.title).toBe('Profile · Telecom Support')
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getByText('Profile')).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Profile' })).toHaveAttribute('aria-current', 'page')
  })

  it('the header shows the display name; the user menu has username, role badges and Sign out', async () => {
    const user = userEvent.setup()
    renderShell(meFixtures.AUD001)
    await h1('Analytics')
    await user.click(screen.getByRole('button', { name: /Business Auditor/ }))
    const menu = await screen.findByRole('menu')
    expect(menu).toHaveTextContent('AUD001')
    expect(within(menu).getByText('Business analyst')).toBeInTheDocument()
    expect(within(menu).getByText('Business auditor')).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: 'Sign out' })).toBeInTheDocument()
  })
})

describe('AppShell mobile drawer (frontend.md §8 < md; NFR-RESP)', () => {
  it('opens a modal drawer that traps focus; Esc closes it and focus returns to the menu button', async () => {
    const user = userEvent.setup()
    renderShell(meFixtures.customer)
    await h1('Home')

    const button = menuButton()
    await user.click(button)
    const drawer = await screen.findByRole('dialog', { name: 'Navigation' })
    // The rest of the page is hidden from assistive technology while the modal drawer is open.
    expect(button).toHaveAttribute('aria-expanded', 'true')
    expect(screen.queryByRole('button', { name: 'Open navigation menu' })).not.toBeInTheDocument()
    for (let i = 0; i < 12; i++) {
      await user.tab()
      expect(drawer).toContainElement(document.activeElement as HTMLElement)
    }

    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(menuButton()).toHaveFocus()
    expect(menuButton()).toHaveAttribute('aria-expanded', 'false')
  })

  it('choosing a link closes the drawer and focus moves to the new page <h1>', async () => {
    const user = userEvent.setup()
    renderShell(meFixtures.customer)
    await h1('Home')
    await user.click(menuButton())
    const drawer = await screen.findByRole('dialog', { name: 'Navigation' })

    await user.click(within(drawer).getByRole('link', { name: 'My plan' }))
    const heading = await h1('My plan')
    await waitFor(() => expect(heading).toHaveFocus())
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('any navigation closes the drawer and focus moves to the new page <h1>', async () => {
    const user = userEvent.setup()
    const { router } = renderShell(meFixtures.customer)
    await h1('Home')
    await user.click(menuButton())
    await screen.findByRole('dialog', { name: 'Navigation' })

    await act(() => router.navigate('/customer/payments'))
    const heading = await h1('Payment history')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(heading).toHaveFocus())
  })
})

describe('AppShell sidebar collapse (frontend.md §5, §8)', () => {
  it('collapses to the icon rail and the preference survives a reload', async () => {
    const user = userEvent.setup()
    renderShell(meFixtures.OUT001)
    await h1('Case queue')
    const toggle = screen.getByRole('button', { name: 'Collapse sidebar' })
    expect(toggle).toHaveAttribute('aria-pressed', 'false')
    expect(within(screen.getByRole('navigation', { name: 'Main' })).getByText('Bookmarks')).not.toHaveClass('sr-only')

    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-pressed', 'true')
    expect(within(screen.getByRole('navigation', { name: 'Main' })).getByText('Bookmarks')).toHaveClass('sr-only')
    expect(JSON.parse(localStorage.getItem('tcs-ui') ?? '{}')).toMatchObject({ state: { sidebarCollapsed: true } })

    // A new page load: fresh in-memory state (the setState also persists, so put the saved value back), then rehydrate.
    const saved = localStorage.getItem('tcs-ui') ?? ''
    cleanup()
    useUiStore.setState({ sidebarCollapsed: null })
    localStorage.setItem('tcs-ui', saved)
    await useUiStore.persist.rehydrate()
    renderShell(meFixtures.OUT001)
    await h1('Case queue')
    expect(screen.getByRole('button', { name: 'Collapse sidebar' })).toHaveAttribute('aria-pressed', 'true')
  })
})

describe('AppShell at md–lg (tablet; frontend.md §8, FC2)', () => {
  const original = Object.getOwnPropertyDescriptor(window, 'matchMedia')
  beforeEach(() => {
    // 768–1023 px: `(min-width: 1024px)` does not match.
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        addEventListener: () => {},
        removeEventListener: () => {},
      }),
    })
  })
  afterEach(() => {
    if (original) Object.defineProperty(window, 'matchMedia', original)
    else Reflect.deleteProperty(window, 'matchMedia')
  })

  const bookmarksLabel = () => within(screen.getByRole('navigation', { name: 'Main' })).getByText('Bookmarks')

  it('starts as the icon rail, and the toggle (shown from md) expands it to show the labels; the choice persists', async () => {
    const user = userEvent.setup()
    renderShell(meFixtures.OUT001)
    await h1('Case queue')
    const toggle = screen.getByRole('button', { name: 'Collapse sidebar' })
    expect(toggle).toHaveClass('md:inline-flex')
    expect(toggle).not.toHaveClass('lg:inline-flex')
    expect(toggle).toHaveAttribute('aria-pressed', 'true')
    expect(bookmarksLabel()).toHaveClass('sr-only')

    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-pressed', 'false')
    expect(bookmarksLabel()).not.toHaveClass('sr-only')
    expect(JSON.parse(localStorage.getItem('tcs-ui') ?? '{}')).toMatchObject({ state: { sidebarCollapsed: false } })

    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-pressed', 'true')
    expect(bookmarksLabel()).toHaveClass('sr-only')
  })

  it('in the rail, Logout has the same tooltip as the nav items (FC13)', async () => {
    const user = userEvent.setup()
    renderShell(meFixtures.OUT001)
    await h1('Case queue')
    const logout = screen.getByRole('button', { name: 'Logout' })
    await user.hover(logout)
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Logout')
  })
})

describe('AppShell axe (NFR-A11Y)', () => {
  it.each([
    ['CUSTOMER', meFixtures.customer, 'Home'],
    ['OUTBOUND_AGENT', meFixtures.OUT001, 'Case queue'],
  ])('%s: the shell is axe clean', async (_role, me, title) => {
    const { container } = renderShell(me)
    await h1(title)
    expect(await axe(container)).toHaveNoViolations()
  })
})

describe('AppShell bootstrap states (frontend.md §3 Application shell; XC-03)', () => {
  const CORRELATION_ID = '0b9d8c7e-1f2a-4b3c-8d4e-5f6a7b8c9d0e'

  it('shows FullPageSpinner while /me loads, a full-page ErrorState on 5xx, and the shell after Try again', async () => {
    const user = userEvent.setup()
    let meStatus = 500
    server.use(
      http.get('*/api/v1/auth/me', () =>
        meStatus === 500
          ? HttpResponse.json(
              { type: 'about:blank', title: 'Internal Server Error', status: 500, detail: 'Try again later.', correlationId: CORRELATION_ID },
              { status: 500, headers: { 'Content-Type': 'application/problem+json', 'X-Correlation-Id': CORRELATION_ID } },
            )
          : HttpResponse.json(meFixtures.customer),
      ),
    )
    // The shell alone (no loader), on a client that does not retry, so the bootstrap states are what renders.
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const router = createMemoryRouter(
      [{ path: '/', element: <AppShell />, children: [{ index: true, element: <h1 tabIndex={-1}>Content</h1> }] }],
      { initialEntries: ['/'] },
    )
    render(
      <QueryClientProvider client={client}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )
    expect(screen.getByRole('status')).toHaveTextContent('Loading')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Try again later.')
    expect(alert).toHaveTextContent(CORRELATION_ID)

    meStatus = 200
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Content' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Main' })).toBeInTheDocument()
    client.clear()
  })

  it('signed out while the router is already heading to /login: the spinner, never a blank screen (FC8)', () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    client.setQueryData(ME_QUERY_KEY, null)
    const router = createMemoryRouter([{ path: '/', element: <AppShell /> }], { initialEntries: ['/'] })
    // The navigator reports the pending /login navigation (logout, the 401 handler), so the shell adds no second redirect.
    unregister = registerNavigator({ navigate: () => {}, location: () => ({ pathname: '/login', search: '', hash: '' }) })
    render(
      <QueryClientProvider client={client}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )
    expect(screen.getByRole('status')).toHaveTextContent('Loading')
    expect(router.state.location.pathname).toBe('/')
    client.clear()
  })
})
