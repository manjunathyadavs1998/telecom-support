import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { axe } from 'vitest-axe'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
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
