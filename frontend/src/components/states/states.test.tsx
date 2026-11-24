import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { axe } from 'vitest-axe'
import { createQueryClient, ME_QUERY_KEY } from '@/app/queryClient'
import { PageContainer } from '@/components/layout/PageContainer'
import { ComingSoonPage } from '@/pages/ComingSoonPage'
import { ForbiddenPage } from '@/pages/ForbiddenPage'
import { NoAccessPage } from '@/pages/NoAccessPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { RouteErrorPage } from '@/pages/RouteErrorPage'
import { NAV_ITEMS } from '@/permissions/navConfig'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import { meFixtures } from '@/test/fixtures/me'
import { server } from '@/test/msw/server'
import type { Me } from '@/types/api'
import { EmptyState } from './EmptyState'
import { ErrorState } from './ErrorState'
import { FormRootError } from './FormRootError'
import { FullPageSpinner, LoadingState } from './LoadingState'

const CORRELATION_ID = '7f1c2a9e-4b3d-4e2f-9a10-123456789abc'

function serverError(): ApiError {
  return new ApiError({
    status: 500,
    problem: {
      type: 'about:blank',
      title: 'Internal Server Error',
      status: 500,
      detail: 'The service is temporarily unavailable.',
      correlationId: CORRELATION_ID,
    },
    correlationId: CORRELATION_ID,
  })
}

describe('LoadingState / FullPageSpinner (XC-03, NFR-A11Y)', () => {
  it.each(['skeleton', 'spinner'] as const)('%s: busy status region with a screen-reader label, axe clean', async (variant) => {
    const { container } = render(<LoadingState label="Loading cases" variant={variant} />)
    const status = screen.getByRole('status')
    expect(status).toHaveAttribute('aria-busy', 'true')
    expect(status).toHaveAttribute('aria-live', 'polite')
    expect(status).toHaveTextContent('Loading cases')
    expect(screen.getByText('Loading cases')).toHaveClass('sr-only')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('FullPageSpinner: busy status region with a default label, axe clean', async () => {
    const { container } = render(<FullPageSpinner />)
    const status = screen.getByRole('status')
    expect(status).toHaveAttribute('aria-busy', 'true')
    expect(status).toHaveTextContent('Loading')
    expect(await axe(container)).toHaveNoViolations()
  })
})

describe('EmptyState (XC-03)', () => {
  it('shows title, description and action; the icon is hidden from assistive technology; axe clean', async () => {
    const { container } = render(
      <EmptyState
        icon={<svg data-testid="icon" />}
        title="No cases yet"
        description="Cases you create appear here."
        action={<button type="button">Create a case</button>}
      />,
    )
    expect(screen.getByRole('heading', { name: 'No cases yet' })).toBeInTheDocument()
    expect(screen.getByText('Cases you create appear here.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create a case' })).toBeInTheDocument()
    expect(screen.getByTestId('icon').parentElement).toHaveAttribute('aria-hidden', 'true')
    expect(await axe(container)).toHaveNoViolations()
  })
})

describe('ErrorState (XC-03, NFR-OBS)', () => {
  it('alerts with the detail and the correlation id; retry calls onRetry; axe clean', async () => {
    const onRetry = vi.fn()
    const user = userEvent.setup()
    const { container } = render(<ErrorState title="Couldn't load cases" error={serverError()} onRetry={onRetry} />)

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent("Couldn't load cases")
    expect(alert).toHaveTextContent('The service is temporarily unavailable.')
    expect(alert).toHaveTextContent(CORRELATION_ID)
    expect(await axe(container)).toHaveNoViolations()

    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('its buttons carry the design focus ring (frontend.md §8 "focus ring on every interactive element", FC4)', () => {
    render(<ErrorState title="Couldn't load cases" error={serverError()} onRetry={() => {}} />)
    for (const name of ['Try again', 'Copy correlation id']) {
      expect(screen.getByRole('button', { name })).toHaveClass('focus-visible:ring-2', 'focus-visible:ring-ring', 'focus-visible:ring-offset-2')
    }
  })

  it('copies the correlation id', async () => {
    const user = userEvent.setup()
    render(<ErrorState title="Couldn't load cases" error={serverError()} />)
    await user.click(screen.getByRole('button', { name: 'Copy correlation id' }))
    expect(await navigator.clipboard.readText()).toBe(CORRELATION_ID)
    expect(screen.getByText('Copied')).toBeInTheDocument()
  })

  it('a non-API error shows a generic message, no correlation id and no retry without onRetry', () => {
    render(<ErrorState title="Something failed" error={new Error('TypeError: x is undefined at foo.ts:12')} />)
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent(GENERIC_ERROR_MESSAGE)
    expect(alert).not.toHaveTextContent('foo.ts')
    expect(screen.queryByText(/Correlation id/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})

describe('FormRootError (forms)', () => {
  it('renders nothing without an error', () => {
    const { container } = render(<FormRootError />)
    expect(container).toBeEmptyDOMElement()
  })

  it('announces the server message and correlation id via role="alert"; axe clean', async () => {
    const { container } = render(<FormRootError error={serverError()} />)
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('The service is temporarily unavailable.')
    expect(alert).toHaveTextContent(CORRELATION_ID)
    expect(await axe(container)).toHaveNoViolations()
  })
})

/** A provider whose `/me` cache already holds `me` (fresh for 60 s, so no request is made). */
function withMe(me: Me | null) {
  const client = createQueryClient()
  client.setQueryData(ME_QUERY_KEY, me)
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  )
}

describe('system pages (frontend.md §3 System pages, §4; XC-02, NFR-A11Y)', () => {
  const requests: string[] = []
  beforeAll(() => {
    server.listen({ onUnhandledFrame: 'error' })
    server.events.on('request:start', ({ request }) => {
      requests.push(request.url)
    })
  })
  afterEach(() => {
    server.resetHandlers()
    requests.length = 0
  })
  afterAll(() => server.close())

  it('PageContainer: focusable <h1>, description, actions and document.title "<Page> · Telecom Support"; axe clean', async () => {
    const { container } = render(
      <PageContainer title="Case queue" description="Open cases" actions={<button type="button">Refresh</button>}>
        <p>content</p>
      </PageContainer>,
    )
    const heading = screen.getByRole('heading', { level: 1, name: 'Case queue' })
    expect(heading).toHaveAttribute('tabindex', '-1')
    heading.focus()
    expect(heading).toHaveFocus()
    expect(screen.getByText('Open cases')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeInTheDocument()
    expect(document.title).toBe('Case queue · Telecom Support')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('ForbiddenPage: title "Access denied", message and a link to the role landing; axe clean', async () => {
    const { container } = render(<ForbiddenPage />, { wrapper: withMe(meFixtures.IN001) })
    expect(screen.getByRole('heading', { level: 1, name: 'Access denied' })).toHaveAttribute('tabindex', '-1')
    expect(document.title).toBe('Access denied · Telecom Support')
    expect(screen.getByText("You don't have access to this page.")).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to your home page' })).toHaveAttribute('href', '/inbound')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('NotFoundPage: "Page not found" and a link to the role landing; axe clean', async () => {
    const { container } = render(<NotFoundPage />, { wrapper: withMe(meFixtures.customer) })
    expect(screen.getByRole('heading', { level: 1, name: 'Page not found' })).toHaveAttribute('tabindex', '-1')
    expect(document.title).toBe('Page not found · Telecom Support')
    expect(screen.getByRole('link', { name: 'Go to your home page' })).toHaveAttribute('href', '/customer')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('NoAccessPage: EmptyState "No features are assigned to your account" with Sign out; axe clean', async () => {
    const onSignOut = vi.fn()
    const user = userEvent.setup()
    const { container } = render(<NoAccessPage onSignOut={onSignOut} />)
    expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute('tabindex', '-1')
    expect(screen.getByRole('heading', { level: 2, name: 'No features are assigned to your account' })).toBeInTheDocument()
    expect(await axe(container)).toHaveNoViolations()
    await user.click(screen.getByRole('button', { name: 'Sign out' }))
    expect(onSignOut).toHaveBeenCalledTimes(1)
  })

  it.each(NAV_ITEMS.map((item) => [item.path, item] as const))(
    'ComingSoonPage %s: title, BRD id and "Available in slice NN"; no API request; axe clean',
    async (_path, item) => {
      // An empty `/me` cache entry would not stop a query; any request here would show up in `requests` or fail via MSW.
      const { container } = render(<ComingSoonPage item={item} />, { wrapper: withMe(null) })
      expect(screen.getByRole('heading', { level: 1, name: item.label })).toHaveAttribute('tabindex', '-1')
      expect(screen.getByText(item.brd)).toBeInTheDocument()
      expect(screen.getByText(`Available in slice ${item.slice}`)).toBeInTheDocument()
      expect(await axe(container)).toHaveNoViolations()
      expect(requests).toEqual([])
    },
  )

  it('RouteErrorPage: ErrorState with retry and correlation id, logs to console.error; axe clean', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const onRetry = vi.fn()
    const user = userEvent.setup()
    const { container } = render(<RouteErrorPage error={serverError()} onRetry={onRetry} />)
    expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute('tabindex', '-1')
    expect(screen.getByRole('alert')).toHaveTextContent(CORRELATION_ID)
    expect(await axe(container)).toHaveNoViolations()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
    expect(consoleError).toHaveBeenCalled()
    consoleError.mockRestore()
  })

  it('RouteErrorPage: a thrown Error shows only the generic message, never a stack or internal text', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const error = new TypeError('x is undefined')
    error.stack = 'TypeError: x is undefined\n    at Loader (routes.tsx:42:7)'
    render(<RouteErrorPage error={error} onRetry={() => {}} />)
    expect(screen.getByRole('alert')).toHaveTextContent(GENERIC_ERROR_MESSAGE)
    expect(document.body).not.toHaveTextContent('routes.tsx')
    expect(document.body).not.toHaveTextContent('x is undefined')
    consoleError.mockRestore()
  })
})
