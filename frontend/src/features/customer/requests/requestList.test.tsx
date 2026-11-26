import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { MemoryRouter, useLocation } from 'react-router'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { axe } from 'vitest-axe'
import { createQueryClient, ME_QUERY_KEY } from '@/app/queryClient'
import { INVALID_LINK_NOTICE } from '@/components/data/useListParams'
import { defaultWindow } from '@/lib/dateWindow'
import { casePage, caseSummary, problem } from '@/test/fixtures/customer'
import { meCustomer } from '@/test/fixtures/me'
import { problemResponse } from '@/test/msw/customerHandlers'
import { server } from '@/test/msw/server'
import type { CasePage, CaseSummary, Me } from '@/types/api'
import { REFERENCE_FORMAT_MESSAGE } from './queries'
import { RequestListPage } from './RequestListPage'

const CASES_URL = '*/api/v1/customers/me/cases'
const window3m = defaultWindow()

let client: QueryClient
let queries: Record<string, string>[]

/** E11 with every request's query recorded; `reply` decides the page per request (default: one row). */
function serve(reply: (query: Record<string, string>) => CasePage | Response | Promise<Response> = () => casePage()) {
  server.use(
    http.get(CASES_URL, async ({ request }) => {
      const query = Object.fromEntries(new URL(request.url).searchParams)
      queries.push(query)
      const result = await reply(query)
      return result instanceof Response ? result : HttpResponse.json(result)
    }),
  )
}

function LocationProbe() {
  const { search } = useLocation()
  return <span data-testid="url">{search}</span>
}

function renderList(path = '/customer/requests', me: Me = meCustomer) {
  client = createQueryClient()
  client.setDefaultOptions({ queries: { retry: false } })
  client.setQueryData(ME_QUERY_KEY, me)
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <RequestListPage />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const url = () => screen.getByTestId('url').textContent ?? ''
const table = () => within(screen.getByTestId('data-table-wide')).getByRole('table')
/** A reference in the wide table (the card list < md repeats it). */
const findInTable = async (text: string) => within(await screen.findByTestId('data-table-wide')).findByText(text)
const rows = () => within(table()).getAllByRole('row').slice(1)

function manyCases(count: number, offset = 0): CaseSummary[] {
  return Array.from({ length: count }, (_, i) => ({
    ...caseSummary,
    reference: `SR-20261125-${String(offset + i + 1).padStart(6, '0')}`,
  }))
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  queries = []
})
afterEach(() => {
  cleanup()
  server.resetHandlers()
  client.clear()
})
afterAll(() => server.close())

describe('Service requests list (frontend.md §3 Service requests; CUS-05, BR-05/06, XC-04)', () => {
  it('default: no dates sent, page 0 size 20; columns with customer-visible labels; axe clean', async () => {
    serve()
    const { container } = renderList()
    expect(screen.getByText('Loading requests')).toBeInTheDocument()
    await findInTable(caseSummary.reference)
    expect(queries).toEqual([{ page: '0', size: '20' }])
    expect(within(table()).getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Reference',
      'Category',
      'Created (IST)',
      'Severity',
      'Status',
    ])
    const cells = within(rows()[0]!).getAllByRole('cell').map((c) => c.textContent)
    expect(cells).toEqual(['SR-20261125-000042', 'Billing › Wrong charge', '25 Nov 2026, 3:30 pm', 'Medium', 'Submitted'])
    expect(screen.getByRole('tab', { name: 'Last 3 months' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('Searches within the selected dates')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'New request' })[0]).toHaveAttribute('href', '/customer/requests/new')
    expect(document.title).toBe('Service requests · Telecom Support')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('custom range: Apply sends from/to and keeps them in the URL', async () => {
    serve()
    const user = userEvent.setup()
    renderList()
    await findInTable(caseSummary.reference)
    await user.click(screen.getByRole('tab', { name: 'Custom range' }))
    fireEvent.change(screen.getByLabelText('From'), { target: { value: window3m.from } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: window3m.to } })
    await user.click(screen.getByRole('button', { name: 'Apply' }))
    await waitFor(() => expect(queries.at(-1)).toEqual({ ...window3m, page: '0', size: '20' }))
    expect(url()).toBe(`?from=${window3m.from}&to=${window3m.to}`)
  })

  it('reference search: normalised, sent inside the selected dates; an invalid reference is a field error, no request', async () => {
    serve()
    const user = userEvent.setup()
    renderList(`/customer/requests?from=${window3m.from}&to=${window3m.to}`)
    await findInTable(caseSummary.reference)
    const box = screen.getByRole('textbox', { name: 'Reference' })
    expect(box).toHaveAccessibleDescription('Searches within the selected dates')

    await user.type(box, 'PAY-1')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByText(REFERENCE_FORMAT_MESSAGE)).toBeInTheDocument()
    expect(box).toHaveAttribute('aria-invalid', 'true')
    expect(queries).toHaveLength(1)

    await user.clear(box)
    await user.type(box, ' sr-20261125-000042 ')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() =>
      expect(queries.at(-1)).toEqual({ ...window3m, reference: 'SR-20261125-000042', page: '0', size: '20' }),
    )

    // Clearing returns to the dates-only filter (served from the cache, so no new request is needed).
    await user.click(screen.getByRole('button', { name: 'Clear search' }))
    await waitFor(() => expect(url()).toBe(`?from=${window3m.from}&to=${window3m.to}`))
    expect(screen.getByRole('textbox', { name: 'Reference' })).toHaveValue('')
  })

  it('paging: 1-based URL page → 0-based request, "Showing 21–40 of 57", filters kept, aria-busy while loading', async () => {
    let release: () => void = () => undefined
    serve(async ({ page }) => {
      if (page === '1') await new Promise<void>((resolve) => (release = resolve))
      return HttpResponse.json(casePage(manyCases(20, Number(page) * 20), Number(page), 20, 57))
    })
    const user = userEvent.setup()
    renderList(`/customer/requests?reference=SR-20261125-000001`)
    await screen.findByText('Showing 1–20 of 57')
    await user.click(screen.getByRole('button', { name: 'Page 2' }))
    await waitFor(() => expect(queries.at(-1)).toEqual({ reference: 'SR-20261125-000001', page: '1', size: '20' }))
    expect(screen.getByTestId('data-table-wide').parentElement).toHaveAttribute('aria-busy', 'true')
    expect(within(screen.getByTestId('data-table-wide')).getByText('SR-20261125-000001')).toBeInTheDocument() // previous page stays visible
    release()
    expect(await findInTable('SR-20261125-000021')).toBeInTheDocument()
    expect(screen.getByTestId('data-table-wide').parentElement).toHaveAttribute('aria-busy', 'false')
    expect(screen.getByText('Showing 21–40 of 57')).toBeInTheDocument()
    expect(url()).toBe('?reference=SR-20261125-000001&page=2')
  })

  it('a page past the end shows "This page is empty" + "Go to page 1"', async () => {
    serve(({ page }) => casePage(page === '0' ? manyCases(3) : [], Number(page), 20, 3))
    const user = userEvent.setup()
    renderList('/customer/requests?page=4')
    await user.click(await screen.findByRole('button', { name: 'Go to page 1' }))
    expect(await screen.findByText('Showing 1–3 of 3')).toBeInTheDocument()
    expect(queries.map((q) => q.page)).toEqual(['3', '0'])
  })

  it('invalid link params are dropped before any request, with a notice', async () => {
    serve()
    renderList('/customer/requests?from=2026-13-01&to=2026-11-01&reference=PAY-1&page=0')
    expect(await screen.findByText(INVALID_LINK_NOTICE)).toBeInTheDocument()
    await findInTable(caseSummary.reference)
    expect(queries).toEqual([{ page: '0', size: '20' }])
  })

  it.each([
    ['/customer/requests', 'No requests in the last 3 months'],
    ['/customer/requests?from=2026-09-01&to=2026-09-30', 'No requests between 1 Sep 2026 and 30 Sep 2026'],
    ['/customer/requests?reference=SR-20261125-000099', 'No request SR-20261125-000099 in these dates'],
  ])('empty %s → "%s" with Change dates and New request', async (path, title) => {
    serve(() => casePage([], 0, 20, 0))
    const user = userEvent.setup()
    renderList(path)
    const heading = await screen.findByRole('heading', { level: 2, name: title })
    const empty = heading.parentElement!
    expect(within(empty).getByRole('link', { name: 'New request' })).toHaveAttribute('href', '/customer/requests/new')
    await user.click(within(empty).getByRole('button', { name: 'Change dates' }))
    await waitFor(() => expect(screen.getByLabelText('From')).toHaveFocus())
    expect(screen.getByRole('tab', { name: 'Custom range' })).toHaveAttribute('aria-selected', 'true')
  })

  it('a 400 on a filter change shows the message under the filters and keeps the last good page', async () => {
    serve(({ reference }) =>
      reference ? problemResponse(problem(400, 'validation', 'The date range is not allowed.')) : casePage(),
    )
    const user = userEvent.setup()
    renderList()
    await findInTable(caseSummary.reference)
    await user.type(screen.getByRole('textbox', { name: 'Reference' }), 'SR-20261125-000001')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The date range is not allowed.')
    expect(await findInTable(caseSummary.reference)).toBeInTheDocument()
  })

  it('a 500 shows ErrorState with retry; retry loads the list', async () => {
    let fail = true
    serve(() => (fail ? problemResponse(problem(500, 'internal', 'Unexpected error.')) : casePage()))
    const user = userEvent.setup()
    renderList()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent("We couldn't load your requests")
    expect(alert).toHaveTextContent('corr-500-internal')
    fail = false
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await findInTable(caseSummary.reference)).toBeInTheDocument()
  })

  it('a 403 on the list renders the forbidden page', async () => {
    serve(() => problemResponse(problem(403, 'forbidden', 'Access denied.')))
    renderList()
    expect(await screen.findByRole('heading', { level: 1, name: 'Access denied' })).toBeInTheDocument()
  })

  it('without case.create there is no New request action', async () => {
    serve(async () => {
      await delay(0)
      return HttpResponse.json(casePage([], 0, 20, 0))
    })
    renderList('/customer/requests', { ...meCustomer, permissions: meCustomer.permissions.filter((p) => p !== 'case.create') })
    await screen.findByRole('heading', { level: 2, name: 'No requests in the last 3 months' })
    expect(screen.queryByRole('link', { name: 'New request' })).not.toBeInTheDocument()
  })
})
