import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useEffect, useRef } from 'react'
import { MemoryRouter, useLocation } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { axe } from 'vitest-axe'
import { z } from 'zod'
import { defaultWindow, minusMonths, todayIst } from '@/lib/dateWindow'
import { DateRangeFilter, IST_HINT, type DateRangeFilterHandle } from './DateRangeFilter'
import { Pagination } from './Pagination'
import { StatusBadge, statusLabel } from './StatusBadge'
import { INVALID_LINK_NOTICE, LIST_PAGE_SIZE, useListParams, type ListApiQuery, type ListParamsSchema } from './useListParams'

const SCHEMA: ListParamsSchema = {
  reference: z
    .string()
    .trim()
    .toUpperCase()
    .regex(/^SR-\d{8}-\d{6,}$/),
}

const window3m = defaultWindow()
const FUTURE = '2999-01-01'

/** A list page in miniature: URL state → filter + pagination, and every API query it would send. */
function ListHarness({ onQuery, totalItems = 57 }: { onQuery: (query: ListApiQuery) => void; totalItems?: number }) {
  const { params, apiQuery, invalidNotice, setFilters, setPage } = useListParams(SCHEMA)
  const filterRef = useRef<DateRangeFilterHandle>(null)
  const location = useLocation()
  const queryKey = JSON.stringify(apiQuery)
  useEffect(() => {
    onQuery(JSON.parse(queryKey) as ListApiQuery)
  }, [queryKey, onQuery])
  const totalPages = Math.ceil(totalItems / LIST_PAGE_SIZE)
  return (
    <div>
      <span data-testid="url">{location.search}</span>
      {invalidNotice ? <p role="status">{INVALID_LINK_NOTICE}</p> : null}
      <DateRangeFilter
        ref={filterRef}
        value={params}
        onApply={(range) => setFilters({ ...range, reference: params.reference })}
        onReset={() => setFilters({ reference: params.reference })}
      />
      <button type="button" onClick={() => filterRef.current?.openCustomRange()}>
        Change dates
      </button>
      <Pagination page={params.page} totalPages={totalPages} totalItems={totalItems} size={LIST_PAGE_SIZE} onPage={setPage} />
    </div>
  )
}

function renderList(url: string, totalItems?: number) {
  const onQuery = vi.fn<(query: ListApiQuery) => void>()
  const utils = render(
    <MemoryRouter initialEntries={[url]}>
      <ListHarness onQuery={onQuery} totalItems={totalItems} />
    </MemoryRouter>,
  )
  return { ...utils, onQuery }
}

const url = () => screen.getByTestId('url').textContent ?? ''

describe('useListParams (frontend.md §3, §4; XC-04)', () => {
  it('default: no dates, page 1 → API page 0, size 20, and no notice', () => {
    const { onQuery } = renderList('/customer/requests')
    expect(onQuery).toHaveBeenCalledTimes(1)
    expect(onQuery).toHaveBeenLastCalledWith({ page: 0, size: 20 })
    expect(screen.queryByText(INVALID_LINK_NOTICE)).not.toBeInTheDocument()
  })

  it('1-based URL page → 0-based API page; valid dates and a normalised reference are kept', () => {
    const { onQuery } = renderList(`/x?from=${window3m.from}&to=${window3m.to}&reference=sr-20261125-000042&page=3`)
    expect(onQuery).toHaveBeenLastCalledWith({ ...window3m, reference: 'SR-20261125-000042', page: 2, size: 20 })
    expect(screen.queryByText(INVALID_LINK_NOTICE)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Page 3' })).toHaveAttribute('aria-current', 'page')
  })

  it.each([
    ['a bad date', `from=2026-02-30&to=${window3m.to}`],
    ['only one date', `from=${window3m.from}`],
    ['from after to', `from=${window3m.to}&to=${window3m.from}`],
    ['a future end date', `from=${window3m.from}&to=${FUTURE}`],
    ['a range over 366 days', `from=${minusMonths(todayIst(), 13)}&to=${todayIst()}`],
    ['a bad reference', 'reference=PAY-1'],
    ['page 0', 'page=0'],
    ['a non-numeric page', 'page=two'],
  ])('%s is dropped before any request, the URL is cleaned and the notice shows', (_name, search) => {
    const { onQuery } = renderList(`/x?${search}`)
    expect(onQuery).toHaveBeenCalledTimes(1)
    expect(onQuery).toHaveBeenLastCalledWith({ page: 0, size: 20 })
    expect(screen.getByRole('status')).toHaveTextContent(INVALID_LINK_NOTICE)
    expect(url()).toBe('')
  })

  it('drops only the invalid param and keeps the valid ones', () => {
    const { onQuery } = renderList(`/x?from=${window3m.from}&to=${window3m.to}&page=-1`)
    expect(onQuery).toHaveBeenLastCalledWith({ ...window3m, page: 0, size: 20 })
    expect(url()).toBe(`?from=${window3m.from}&to=${window3m.to}`)
    expect(screen.getByText(INVALID_LINK_NOTICE)).toBeInTheDocument()
  })

  it('filters are kept across pages and a reload; a filter change resets the page and clears the notice', async () => {
    const user = userEvent.setup()
    const { onQuery, unmount } = renderList('/x?page=0')
    expect(screen.getByText(INVALID_LINK_NOTICE)).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Custom range' }))
    fireEvent.change(screen.getByLabelText('From'), { target: { value: window3m.from } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: window3m.to } })
    await user.click(screen.getByRole('button', { name: 'Apply' }))
    expect(url()).toBe(`?from=${window3m.from}&to=${window3m.to}`)
    expect(screen.queryByText(INVALID_LINK_NOTICE)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(url()).toBe(`?from=${window3m.from}&to=${window3m.to}&page=2`)
    expect(onQuery).toHaveBeenLastCalledWith({ ...window3m, page: 1, size: 20 })

    // Reload: the same URL restores the same query and the Custom range tab.
    const saved = url()
    unmount()
    const reloaded = renderList(`/x${saved}`)
    expect(reloaded.onQuery).toHaveBeenLastCalledWith({ ...window3m, page: 1, size: 20 })
    expect(screen.getByRole('tab', { name: 'Custom range' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByLabelText('From')).toHaveValue(window3m.from)

    // Back to the default window: dates removed, page reset.
    await user.click(screen.getByRole('tab', { name: 'Last 3 months' }))
    expect(url()).toBe('')
    expect(reloaded.onQuery).toHaveBeenLastCalledWith({ page: 0, size: 20 })
  })
})

describe('DateRangeFilter (frontend.md §4, §6; CUS-05.1/05.3, XC-05)', () => {
  function renderFilter(value: { from?: string; to?: string } = {}) {
    const onApply = vi.fn()
    const onReset = vi.fn()
    const utils = render(<DateRangeFilter value={value} onApply={onApply} onReset={onReset} />)
    return { ...utils, onApply, onReset }
  }

  async function applyRange(from: string, to: string) {
    const user = userEvent.setup()
    await user.click(screen.getByRole('tab', { name: 'Custom range' }))
    fireEvent.change(screen.getByLabelText('From'), { target: { value: from } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: to } })
    await user.click(screen.getByRole('button', { name: 'Apply' }))
  }

  it('starts on Last 3 months; tabs use manual activation (arrows move focus, Enter selects)', async () => {
    const user = userEvent.setup()
    renderFilter()
    const last3 = screen.getByRole('tab', { name: 'Last 3 months' })
    const custom = screen.getByRole('tab', { name: 'Custom range' })
    expect(last3).toHaveAttribute('aria-selected', 'true')
    await user.click(last3)
    await user.keyboard('{ArrowRight}')
    expect(custom).toHaveFocus()
    expect(custom).toHaveAttribute('aria-selected', 'false')
    await user.keyboard('{Enter}')
    expect(custom).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText(IST_HINT)).toBeInTheDocument()
    expect(screen.getByLabelText('From')).toHaveAttribute('type', 'date')
  })

  it('the custom range is prefilled with the 3-month window and applies a valid range', async () => {
    const { onApply } = renderFilter()
    await userEvent.setup().click(screen.getByRole('tab', { name: 'Custom range' }))
    expect(screen.getByLabelText('From')).toHaveValue(window3m.from)
    expect(screen.getByLabelText('To')).toHaveValue(window3m.to)
    await applyRange(window3m.from, window3m.to)
    expect(onApply).toHaveBeenCalledWith(window3m)
  })

  it.each([
    ['empty start', '', window3m.to, 'From', 'Enter a start date.'],
    ['from after to', window3m.to, window3m.from, 'From', 'The start date must be on or before the end date.'],
    ['future end', window3m.from, FUTURE, 'To', "The end date can't be in the future."],
    ['367 days', minusMonths(todayIst(), 13), todayIst(), 'To', 'The date range can be at most 366 days.'],
  ])('%s: inline error linked to the field, focus moves there, nothing applied', async (_name, from, to, label, message) => {
    const { onApply } = renderFilter()
    await applyRange(from, to)
    const field = screen.getByLabelText(label)
    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(field).toHaveAttribute('aria-invalid', 'true')
    expect(field).toHaveAccessibleDescription(message)
    expect(field).toHaveFocus()
    expect(onApply).not.toHaveBeenCalled()
  })

  it('choosing Last 3 months with a custom range applied calls onReset', async () => {
    const { onReset } = renderFilter(window3m)
    expect(screen.getByRole('tab', { name: 'Custom range' })).toHaveAttribute('aria-selected', 'true')
    await userEvent.setup().click(screen.getByRole('tab', { name: 'Last 3 months' }))
    expect(onReset).toHaveBeenCalledTimes(1)
  })

  it('"Change dates" (openCustomRange) selects Custom range and focuses the start date', async () => {
    renderList('/x')
    await userEvent.setup().click(screen.getByRole('button', { name: 'Change dates' }))
    expect(screen.getByRole('tab', { name: 'Custom range' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByLabelText('From')).toHaveFocus()
  })

  it('is axe clean on both tabs', async () => {
    const { container } = renderFilter(window3m)
    expect(await axe(container)).toHaveNoViolations()
    await userEvent.setup().click(screen.getByRole('tab', { name: 'Last 3 months' }))
    expect(await axe(container)).toHaveNoViolations()
  })
})

describe('Pagination (frontend.md §3, §4)', () => {
  it('"Showing 21–40 of 57", current page marked, Previous/Next move one page', async () => {
    const user = userEvent.setup()
    const onPage = vi.fn()
    const { container } = render(<Pagination page={2} totalPages={3} totalItems={57} size={20} onPage={onPage} />)
    expect(screen.getByText('Showing 21–40 of 57')).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Pagination' })
    expect(within(nav).getByRole('button', { name: 'Page 2' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('button', { name: 'Page 1' })).not.toHaveAttribute('aria-current')
    await user.click(within(nav).getByRole('button', { name: 'Next' }))
    await user.click(within(nav).getByRole('button', { name: 'Previous' }))
    await user.click(within(nav).getByRole('button', { name: 'Page 3' }))
    expect(onPage.mock.calls).toEqual([[3], [1], [3]])
    expect(await axe(container)).toHaveNoViolations()
  })

  it('first/last page disable Previous/Next; the last page shows the partial count', () => {
    const { rerender } = render(<Pagination page={1} totalPages={3} totalItems={57} size={20} onPage={() => {}} />)
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    rerender(<Pagination page={3} totalPages={3} totalItems={57} size={20} onPage={() => {}} />)
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    expect(screen.getByText('Showing 41–57 of 57')).toBeInTheDocument()
  })

  it('many pages: first, last and neighbours with gaps', () => {
    render(<Pagination page={10} totalPages={20} totalItems={400} size={20} onPage={() => {}} />)
    const pages = within(screen.getByRole('navigation')).getAllByRole('button', { name: /^Page / })
    expect(pages.map((b) => b.textContent)).toEqual(['1', '9', '10', '11', '20'])
  })

  it('a single page shows the count without the nav; no items renders nothing', () => {
    const { rerender, container } = render(<Pagination page={1} totalPages={1} totalItems={5} size={20} onPage={() => {}} />)
    expect(screen.getByText('Showing 1–5 of 5')).toBeInTheDocument()
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    rerender(<Pagination page={1} totalPages={0} totalItems={0} size={20} onPage={() => {}} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('a page past the end shows "This page is empty" and "Go to page 1"', async () => {
    const user = userEvent.setup()
    const { onQuery } = renderList('/x?page=9', 57)
    expect(onQuery).toHaveBeenLastCalledWith({ page: 8, size: 20 })
    expect(screen.getByText('This page is empty.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Go to page 1' }))
    expect(url()).toBe('')
    expect(onQuery).toHaveBeenLastCalledWith({ page: 0, size: 20 })
  })
})

describe('StatusBadge (frontend.md §3 Payment status, §4; XC-08)', () => {
  it.each([
    ['PENDING', 'Processing'],
    ['SUCCEEDED', 'Paid'],
    ['FAILED', 'Failed'],
    ['EXPIRED', 'Expired'],
    ['REVIEW', 'Under review'],
  ] as const)('payment %s → "%s" with an icon', (value, label) => {
    render(<StatusBadge kind="payment" value={value} />)
    const badge = screen.getByText(label)
    expect(badge.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  })

  it('labels case, severity and subscription values; axe clean', async () => {
    const { container } = render(
      <>
        <StatusBadge kind="case" value="AWAITING_YOUR_REPLY" />
        <StatusBadge kind="severity" value="CRITICAL" />
        <StatusBadge kind="subscription" value="SUSPENDED" />
      </>,
    )
    expect(screen.getByText('Awaiting your reply')).toBeInTheDocument()
    expect(screen.getByText('Critical')).toBeInTheDocument()
    expect(screen.getByText('Suspended')).toBeInTheDocument()
    expect(statusLabel({ kind: 'case', value: 'IN_PROGRESS' })).toBe('In progress')
    expect(await axe(container)).toHaveNoViolations()
  })
})
