import { QueryClientProvider } from '@tanstack/react-query'
import { render, renderHook, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { createQueryClient, ME_QUERY_KEY } from '@/app/queryClient'
import { meCustomer, meInbound, meInboundApprover, meOutbound, meSupervisor } from '@/test/fixtures/me'
import type { Me } from '@/types/api'
import { Can } from './Can'
import { usePermissions } from './usePermissions'

/** A provider whose `/me` cache already holds `me` (fresh for 60 s, so no request is made). */
function withMe(me: Me | null) {
  const client = createQueryClient()
  client.setQueryData(ME_QUERY_KEY, me)
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

describe('usePermissions (BR-10)', () => {
  it('is set from the /me permissions and user type', () => {
    const { result } = renderHook(() => usePermissions(), { wrapper: withMe(meSupervisor) })
    expect([...result.current.permissions].sort()).toEqual([...meSupervisor.permissions].sort())
    expect(result.current.userType).toBe('STAFF')
    expect(result.current.can(['case.assign', 'case.assign.any'])).toBe(true)
    expect(result.current.can(['case.assign.any'], 'CUSTOMER')).toBe(false)
  })

  it('is empty when signed out', () => {
    const { result } = renderHook(() => usePermissions(), { wrapper: withMe(null) })
    expect(result.current.permissions.size).toBe(0)
    expect(result.current.userType).toBeNull()
    expect(result.current.can([])).toBe(false)
  })
})

describe('Can (UI gating only; the server enforces)', () => {
  it('shows children when every permission is held', () => {
    render(
      <Can allOf={['service.reactivate']}>
        <button type="button">Reactivate</button>
      </Can>,
      { wrapper: withMe(meInboundApprover) },
    )
    expect(screen.getByRole('button', { name: 'Reactivate' })).toBeInTheDocument()
  })

  it('hides children when a permission is missing', () => {
    render(
      <Can allOf={['customer.lookup', 'service.reactivate']}>
        <button type="button">Reactivate</button>
      </Can>,
      { wrapper: withMe(meInbound) },
    )
    expect(screen.queryByRole('button', { name: 'Reactivate' })).not.toBeInTheDocument()
  })

  it('renders the fallback instead when given', () => {
    render(
      <Can allOf={['case.assign.any']} fallback={<p>Assign to me only</p>}>
        <button type="button">Assign to anyone</button>
      </Can>,
      { wrapper: withMe(meOutbound) },
    )
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByText('Assign to me only')).toBeInTheDocument()
  })

  it.each([
    ['hides from a STAFF user holding plan.read', meInbound, false],
    ['shows to a CUSTOMER', meCustomer, true],
  ])('checks the user type: %s', (_name, me, visible) => {
    render(
      <Can allOf={['plan.read']} userType="CUSTOMER">
        <p>My plan</p>
      </Can>,
      { wrapper: withMe(me) },
    )
    expect(screen.queryByText('My plan') !== null).toBe(visible)
  })

  it('hides children when signed out', () => {
    render(
      <Can allOf={[]}>
        <p>Anything</p>
      </Can>,
      { wrapper: withMe(null) },
    )
    expect(screen.queryByText('Anything')).not.toBeInTheDocument()
  })
})
