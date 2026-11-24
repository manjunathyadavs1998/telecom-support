import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/router'
import { customerMe, mockApi, renderWithProviders } from '@/test/utils'

describe('LoginPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('validates required fields before calling the API', async () => {
    const fetchSpy = mockApi([{ path: '/api/v1/auth/me', status: 401, body: {} }])
    renderWithProviders(routes, { route: '/login' })

    await userEvent.click(await screen.findByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Enter your mobile number or employee ID')).toBeInTheDocument()
    expect(screen.getByText('Enter your password')).toBeInTheDocument()
    expect(screen.getByLabelText(/Mobile number or employee ID/)).toHaveAttribute('aria-invalid', 'true')
    expect(fetchSpy.mock.calls.some(([url]) => String(url).includes('/auth/login'))).toBe(false)
  })

  it('shows the server error message for bad credentials', async () => {
    mockApi([
      { path: '/api/v1/auth/me', status: 401, body: {} },
      {
        method: 'POST',
        path: '/api/v1/auth/login',
        status: 401,
        body: { code: 'BAD_CREDENTIALS', detail: 'The mobile number / employee ID or password is incorrect.' },
      },
    ])
    renderWithProviders(routes, { route: '/login' })

    await userEvent.type(await screen.findByLabelText(/Mobile number or employee ID/), '+919876543210')
    await userEvent.type(screen.getByLabelText(/Password/), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('incorrect')
  })

  it('signs in, sends the CSRF header, and lands on the role workspace', async () => {
    const fetchSpy = mockApi([
      { path: '/api/v1/auth/me', status: 401, body: {} },
      { method: 'POST', path: '/api/v1/auth/login', body: customerMe },
    ])
    const { router } = renderWithProviders(routes, { route: '/login' })

    await userEvent.type(await screen.findByLabelText(/Mobile number or employee ID/), '+919876543210')
    await userEvent.type(screen.getByLabelText(/Password/), 'Password@123')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Customer dashboard' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/customer')
    const loginCall = fetchSpy.mock.calls.find(([url]) => String(url).includes('/auth/login'))
    const headers = (loginCall?.[1]?.headers ?? {}) as Record<string, string>
    expect(headers['X-XSRF-TOKEN']).toBe('test-token')
  })
})
