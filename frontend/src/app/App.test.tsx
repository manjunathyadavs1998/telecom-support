import { screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { customerMe, mockApi, outboundMe, renderWithProviders } from '@/test/utils'
import { routes } from './router'

describe('role-aware application shell (XC-01, XC-02)', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows only customer navigation to a customer', async () => {
    mockApi([{ path: '/api/v1/auth/me', body: customerMe }])
    renderWithProviders(routes, { route: '/customer' })

    const nav = await screen.findByRole('navigation', { name: 'Main' })
    expect(within(nav).getByRole('link', { name: 'Service Requests' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Payment History' })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: 'Case Queue' })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: 'Analytics' })).not.toBeInTheDocument()
  })

  it('shows outbound navigation (queue + bookmarks) to an outbound agent', async () => {
    mockApi([{ path: '/api/v1/auth/me', body: outboundMe }])
    renderWithProviders(routes, { route: '/outbound' })

    const nav = await screen.findByRole('navigation', { name: 'Main' })
    expect(within(nav).getByRole('link', { name: 'Case Queue' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Bookmarks' })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: 'Service Requests' })).not.toBeInTheDocument()
  })

  it('denies direct navigation to a route outside the role', async () => {
    mockApi([{ path: '/api/v1/auth/me', body: customerMe }])
    const { router } = renderWithProviders(routes, { route: '/outbound' })

    expect(await screen.findByText("You don't have access to this page")).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/forbidden')
  })

  it('redirects to login when there is no session', async () => {
    mockApi([{ path: '/api/v1/auth/me', status: 401, body: { code: 'UNAUTHENTICATED' } }])
    const { router } = renderWithProviders(routes, { route: '/customer' })

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
  })

  it('sends each role to its own workspace from /', async () => {
    mockApi([{ path: '/api/v1/auth/me', body: outboundMe }])
    const { router } = renderWithProviders(routes, { route: '/' })

    expect(await screen.findByRole('heading', { name: 'Case queue' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/outbound')
  })
})
