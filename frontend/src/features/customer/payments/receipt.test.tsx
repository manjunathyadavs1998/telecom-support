import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { axe } from 'vitest-axe'
import { createQueryClient, ME_QUERY_KEY } from '@/app/queryClient'
import { paymentPending, problem, receipt } from '@/test/fixtures/customer'
import { meCustomer } from '@/test/fixtures/me'
import { problemResponse } from '@/test/msw/customerHandlers'
import { server } from '@/test/msw/server'
import { PAYMENT_NOT_FOUND_TITLE } from './PaymentNotFound'
import { RECEIPT_UNAVAILABLE_MESSAGE, ReceiptPage } from './ReceiptPage'

const ME = '*/api/v1/customers/me'
let client: QueryClient | undefined

function renderPage(reference = receipt.paymentReference) {
  client = createQueryClient()
  client.setDefaultOptions({ queries: { retry: false } })
  client.setQueryData(ME_QUERY_KEY, meCustomer)
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/customer/payments/${reference}/receipt`]}>
        <Routes>
          <Route path="/customer/payments/:reference/receipt" element={<ReceiptPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function failWith(status: number) {
  server.use(http.get(`${ME}/payments/:reference/receipt`, () => problemResponse(problem(status, 'internal', 'Something went wrong.'))))
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
  client?.clear()
  client = undefined
})
afterAll(() => server.close())

describe('Receipt (frontend.md §3 Receipt; CUS-07.4, CUS-08.5)', () => {
  it('renders every E17 field, IST time and INR amount', async () => {
    renderPage()
    const doc = await screen.findByRole('region', { name: `Receipt for ${receipt.paymentReference}` })
    const value = (term: string) => within(doc).getByText(term).nextElementSibling
    expect(screen.getByRole('heading', { level: 1, name: 'Receipt' })).toBeInTheDocument()
    expect(value('Customer')).toHaveTextContent('Customer One')
    expect(value('Account')).toHaveTextContent('ACC-000001')
    expect(value('Invoice')).toHaveTextContent(receipt.invoiceRef)
    expect(value('Payment reference')).toHaveTextContent(receipt.paymentReference)
    expect(value('Transaction id')).toHaveTextContent('MOCK-TXN-000008')
    expect(value('Method')).toHaveTextContent('UPI')
    expect(value('Amount')).toHaveTextContent('₹589.82')
    expect(value('Paid at (IST)')).toHaveTextContent('25 Nov 2026, 3:32 pm')
  })

  it('"Print or save as PDF" opens the browser print dialog; the buttons are hidden in print', async () => {
    const print = vi.spyOn(window, 'print').mockImplementation(() => undefined)
    const user = userEvent.setup()
    renderPage()
    const button = await screen.findByRole('button', { name: 'Print or save as PDF' })
    await user.click(button)
    expect(print).toHaveBeenCalledTimes(1)
    expect(button.parentElement).toHaveClass('print:hidden')
    expect(screen.getByRole('link', { name: 'Payment history' }).parentElement).toHaveClass('print:hidden')
  })

  it('422 (payment not succeeded): explains and links to the status page; no print button', async () => {
    renderPage(paymentPending.reference)
    expect(await screen.findByText(RECEIPT_UNAVAILABLE_MESSAGE)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View the payment status' })).toHaveAttribute(
      'href',
      `/customer/payments/${paymentPending.reference}`,
    )
    expect(screen.queryByRole('button', { name: 'Print or save as PDF' })).not.toBeInTheDocument()
  })

  it('404 shows the not-found ErrorState with a Payment history link and no print button (FC5)', async () => {
    failWith(404)
    renderPage()
    const alert = await screen.findByRole('alert')
    expect(within(alert).getByRole('heading', { name: PAYMENT_NOT_FOUND_TITLE })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Payment history' })).toHaveAttribute('href', '/customer/payments')
    expect(screen.queryByRole('button', { name: 'Print or save as PDF' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 1, name: 'Page not found' })).not.toBeInTheDocument()
  })

  it('500 shows ErrorState with retry', async () => {
    failWith(500)
    renderPage()
    expect(await screen.findByRole('heading', { name: "We couldn't load this receipt" })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('loading state, then axe clean', async () => {
    const { container } = renderPage()
    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true')
    await screen.findByRole('button', { name: 'Print or save as PDF' })
    expect(await axe(container)).toHaveNoViolations()
  })
})
